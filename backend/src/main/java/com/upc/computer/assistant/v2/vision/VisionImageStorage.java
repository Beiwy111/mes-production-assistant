package com.upc.computer.assistant.v2.vision;

import com.fasterxml.jackson.databind.JsonNode;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Bounded decoding of actual JPEG/PNG bytes. No caller-controlled filesystem names. */
final class VisionImageStorage {
    static final int MAX_IMAGE_BYTES=10*1024*1024;
    static final long MAX_PIXELS=40_000_000L;
    static final List<String> VARIANTS=List.of("original","input","annotated");
    static final Object WRITE_LOCK=new Object();
    record Prepared(byte[] bytes,Map<String,Object> metadata) {}

    static Prepared prepare(byte[] bytes,String source) {
        if(bytes==null || bytes.length==0)return unavailable("MISSING",source,"IMAGE_NOT_PROVIDED");
        try {return validate(bytes,source);}catch(IOException | RuntimeException invalid){return unavailable("INVALID",source,"INVALID_OR_OVERSIZE_IMAGE");}
    }
    static Prepared annotation(Object value) {
        String source="SERVER_RESULT_IMAGE";
        if(value==null || value instanceof String text && text.isBlank())return unavailable("MISSING",source,"MODEL_ANNOTATION_NOT_RETURNED");
        if(!(value instanceof String text)||text.length()>MAX_IMAGE_BYTES*4L/3+128)return unavailable("INVALID",source,"INVALID_OR_OVERSIZE_IMAGE");
        try {
            String declared=null;
            if(text.startsWith("data:")) {
                int comma=text.indexOf(',');if(comma<0)throw new IllegalArgumentException();
                declared=text.substring(0,comma);
                if(!Set.of("data:image/png;base64","data:image/jpeg;base64").contains(declared))throw new IllegalArgumentException();
                text=text.substring(comma+1);
            }
            Prepared image=prepare(Base64.getDecoder().decode(text),source);
            if(declared!=null && image.bytes()!=null && !declared.startsWith("data:"+image.metadata().get("mimeType")+";"))throw new IllegalArgumentException();
            return image;
        }catch(IllegalArgumentException invalid){return unavailable("INVALID",source,"INVALID_OR_OVERSIZE_IMAGE");}
    }
    static Prepared validate(byte[] bytes,String source) throws IOException {
        if(bytes==null || bytes.length==0 || bytes.length>MAX_IMAGE_BYTES)throw new IOException("Image size limit");
        String mime;
        if(bytes.length>=8 && Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10}))mime="image/png";
        else if(bytes.length>=3 && (bytes[0]&255)==255 && (bytes[1]&255)==216 && (bytes[2]&255)==255)mime="image/jpeg";
        else throw new IOException("Unsupported image bytes");
        try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("Image decoder unavailable");
            ImageReader reader=readers.next();
            try {
                reader.setInput(input,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<=0 || height<=0 || (long)width*height>MAX_PIXELS)throw new IOException("Image dimension limit");
                var decoded=reader.read(0);if(decoded==null || decoded.getWidth()!=width || decoded.getHeight()!=height)throw new IOException("Invalid image");
                decoded.flush();
                Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("status","AVAILABLE");metadata.put("mimeType",mime);metadata.put("sha256",sha256(bytes));metadata.put("byteSize",bytes.length);metadata.put("width",width);metadata.put("height",height);metadata.put("source",source);
                return new Prepared(bytes,metadata);
            }finally{reader.dispose();}
        }catch(RuntimeException invalid){throw new IOException("Invalid image",invalid);}
    }
    static Prepared unavailable(String status,String source,String reason) {
        Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("status",status);metadata.put("mimeType",null);metadata.put("sha256",null);metadata.put("byteSize",null);metadata.put("width",null);metadata.put("height",null);metadata.put("source",source);metadata.put("reason",reason);return new Prepared(null,metadata);
    }
    static String sha256(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    static Path path(Path folder,String recordId,String variant,String mime) throws IOException {
        if(recordId==null || !recordId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") || !VARIANTS.contains(variant) || !Set.of("image/png","image/jpeg").contains(mime))throw new IOException("Invalid image reference");
        return folder.resolve(recordId+"-"+variant+(mime.equals("image/png")?".png":".jpg"));
    }
    static boolean validMetadata(JsonNode images) {
        if(!images.isObject() || images.size()!=3)return false;
        Set<String> fields=Set.of("status","mimeType","sha256","byteSize","width","height","source","reason");
        for(String variant:VARIANTS) {
            JsonNode m=images.path(variant);if(!m.isObject() || !Set.of("AVAILABLE","MISSING","INVALID").contains(m.path("status").asText()))return false;
            var names=m.fieldNames();while(names.hasNext())if(!fields.contains(names.next()))return false;
            String expectedSource=switch(variant){case "original"->"ORIGINAL_UPLOAD";case "input"->"ACTUAL_INFERENCE_INPUT";default->"SERVER_RESULT_IMAGE";};
            if(!m.path("source").asText().equals(expectedSource))return false;
            if("AVAILABLE".equals(m.path("status").asText()) && (!Set.of("image/png","image/jpeg").contains(m.path("mimeType").asText()) || !m.path("sha256").asText().matches("[0-9a-f]{64}") || m.path("byteSize").asLong()<=0 || m.path("byteSize").asLong()>MAX_IMAGE_BYTES || m.path("width").asLong()<=0 || m.path("height").asLong()<=0 || m.path("width").asLong()>MAX_PIXELS || m.path("height").asLong()>MAX_PIXELS || m.path("width").asLong()*m.path("height").asLong()>MAX_PIXELS))return false;
        }
        return "AVAILABLE".equals(images.path("input").path("status").asText());
    }
    static boolean quotaAllows(Path root,long required) throws IOException {
        long budget=Long.getLong("assistant.vision.max-storage-bytes",2L*1024*1024*1024);
        if(required>budget || budget<=0)return false;
        long used=0;
        try(var paths=Files.walk(root)) {
            for(Iterator<Path> it=paths.iterator();it.hasNext();) {
                Path p=it.next();if(Files.isSymbolicLink(p))throw new IOException("Unsafe archive path");
                if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)) {long size=Files.size(p);if(size>budget-required-used)return false;used+=size;}
            }
        }
        return true;
    }
}

package com.upc.computer.assistant.v2.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Private, append-only sidecar. Never changes inspection results; manifests commit after private image files. */
public final class VisionEvidenceArchive {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BYTES=262144, MAX_PER_INSPECTION=100, MAX_RECORDS=40, MAX_READ_BYTES=65536;
    private static final Set<String> RECORD_FIELDS=Set.of("schemaVersion","recordId","inspectionId","recordedAt","source","imageSha256","imageRetention","imageHashBasis","model","algorithmVersion","modelProvenance","algorithmVersionProvenance","weightsVersion","mock","rawCoreProvenance","defect","count","maxConfidence","summary","detections","inspectionNo","batchNo","workOrderId","materialId","bindingSource","images");
    private final Path root;
    private final Clock clock;
    public VisionEvidenceArchive(Path root, Clock clock) { this.root=root.toAbsolutePath().normalize(); this.clock=clock; }
    public static VisionEvidenceArchive configured() { return new VisionEvidenceArchive(defaultRoot(),Clock.systemUTC()); }
    public static Path defaultRoot() {
        String configured=System.getProperty("assistant.vision.evidence-dir",System.getenv("ASSISTANT_VISION_EVIDENCE_DIR"));
        if(configured!=null && !configured.isBlank()) {
            Path path=Path.of(configured);
            if(!path.isAbsolute()) throw new IllegalArgumentException("Vision evidence directory must be absolute");
            return path.normalize();
        }
        return rootFor(Path.of(""));
    }
    public static Path rootFor(Path workingDirectory) {
        Path cwd=workingDirectory.toAbsolutePath().normalize();
        if(Files.isDirectory(cwd.resolve("backend/assistant"))) return cwd.resolve("backend/assistant/data/vision-evidence");
        if(Files.isDirectory(cwd.resolve("assistant"))) return cwd.resolve("assistant/data/vision-evidence");
        throw new IllegalStateException("Cannot locate backend assistant directory; configure ASSISTANT_VISION_EVIDENCE_DIR");
    }
    public Map<String,Object> save(long inspectionId,byte[] image,Map<String,Object> serverResult) {
        synchronized(VisionImageStorage.WRITE_LOCK) {return saveInternal(inspectionId,image,null,serverResult,null);}
    }
    public Map<String,Object> save(long inspectionId,byte[] input,byte[] original,Map<String,Object> serverResult,Map<String,Object> binding) {
        synchronized(VisionImageStorage.WRITE_LOCK) {return saveInternal(inspectionId,input,original,serverResult,binding==null?Map.of():binding);}
    }
    private Map<String,Object> saveInternal(long inspectionId,byte[] image,byte[] original,Map<String,Object> serverResult,Map<String,Object> binding) {
        Path pending=null; List<Path> created=new ArrayList<>(); boolean committed=false;
        try {
            if(inspectionId<=0 || image==null || image.length==0) throw new IllegalArgumentException();
            if(!Boolean.TRUE.equals(serverResult.get("rawResultValid"))) return status("NOT_SAVED","INVALID_MODEL_RESULT");
            Map<String,VisionImageStorage.Prepared> prepared=new LinkedHashMap<>();
            if(binding!=null) {
                prepared.put("input",VisionImageStorage.prepare(image,"ACTUAL_INFERENCE_INPUT"));
                if(prepared.get("input").bytes()==null)return status("NOT_SAVED","INVALID_INPUT_IMAGE");
                prepared.put("original",VisionImageStorage.prepare(original,"ORIGINAL_UPLOAD"));
                prepared.put("annotated",VisionImageStorage.annotation(serverResult.get("resultImage")));
            }
            Path folder=folder(inspectionId);
            checkExistingAncestors(folder);
            Files.createDirectories(folder); checkPath(root); checkPath(folder);
            // Refuse an unbounded archive. Existing records remain available and unchanged.
            try(var files=Files.list(folder)) { if(files.filter(p->p.getFileName().toString().endsWith(".json")).limit(MAX_PER_INSPECTION).count()>=MAX_PER_INSPECTION) return status("NOT_SAVED","RECORD_LIMIT_REACHED"); }
            String recordId=UUID.randomUUID().toString();
            Map<String,Object> record=new LinkedHashMap<>();
            record.put("schemaVersion",1); record.put("recordId",recordId); record.put("inspectionId",String.valueOf(inspectionId));
            record.put("recordedAt",clock.instant().toString()); record.put("source","SERVER_YOLO_RESPONSE");
            record.put("imageSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image)));
            record.put("imageRetention","IMAGE_NOT_RETAINED"); record.put("imageHashBasis","UPLOADED_BYTES_AFTER_CLIENT_COMPRESSION");
            record.put("model",bounded(serverResult.get("model"),160));
            record.put("algorithmVersion",bounded(serverResult.get("algorithmVersion"),160));
            record.put("modelProvenance",bounded(serverResult.getOrDefault("modelProvenance","UNVERIFIED_NORMALIZED_MODEL_LABEL"),100));
            record.put("algorithmVersionProvenance",bounded(serverResult.getOrDefault("algorithmVersionProvenance","UNVERIFIED_NORMALIZED_ALGORITHM_LABEL"),100));
            record.put("weightsVersion",bounded(serverResult.getOrDefault("weightsVersion","WEIGHTS_VERSION_UNAVAILABLE"),160));
            boolean mock=Boolean.TRUE.equals(serverResult.get("mock")) || String.valueOf(serverResult.get("algorithmVersion")).startsWith("MOCK") || String.valueOf(serverResult.get("model")).toLowerCase(Locale.ROOT).contains("mock");
            record.put("mock",mock); record.put("defect",Boolean.TRUE.equals(serverResult.get("defect")));
            record.put("rawCoreProvenance",mock?"DECLARED_MOCK_GENERATOR":"SERVER_FIELDS_VALIDATED_BEFORE_NORMALIZATION");
            record.put("count",JSON.valueToTree(serverResult).path("count").asInt(0));
            record.put("maxConfidence",JSON.valueToTree(serverResult).path("maxConfidence").asDouble(0));
            record.put("summary",bounded(serverResult.get("summary"),2000));
            List<Map<String,Object>> detections=new ArrayList<>();
            JsonNode input=JSON.valueToTree(serverResult.getOrDefault("detections",List.of()));
            if(!input.isArray() || input.size()>200) throw new IllegalArgumentException();
            for(JsonNode d:input) {
                List<Double> box=new ArrayList<>(); if(!d.path("box").isArray() || d.path("box").size()!=4) throw new IllegalArgumentException();
                for(JsonNode coordinate:d.path("box")) { double value=coordinate.asDouble(Double.NaN); if(!Double.isFinite(value))throw new IllegalArgumentException(); box.add(value); }
                double confidence=d.path("confidence").asDouble(Double.NaN); if(!Double.isFinite(confidence)||confidence<0||confidence>1)throw new IllegalArgumentException();
                detections.add(Map.of("classId",d.path("classId").asInt(),"className",bounded(d.path("className").asText(),100),"confidence",confidence,"box",box));
            }
            record.put("detections",detections);
            if(binding!=null) {
                record.put("schemaVersion",2);
                for(String field:List.of("inspectionNo","batchNo","workOrderId","materialId"))record.put(field,binding.get(field)==null?null:bounded(binding.get(field),160));
                record.put("bindingSource","INSPECTION_RECORD_AT_DETECTION");
                Map<String,Object> images=new LinkedHashMap<>();prepared.forEach((variant,value)->images.put(variant,value.metadata()));record.put("images",images);
                record.put("imageRetention",prepared.values().stream().allMatch(value->value.bytes()!=null)?"IMAGES_RETAINED":"PARTIAL_IMAGES");
            }
            byte[] bytes=JSON.writeValueAsBytes(record);
            if(bytes.length>MAX_BYTES || !validRecord(JSON.readTree(bytes),inspectionId,recordId)) throw new IllegalArgumentException();
            long total=bytes.length;for(var value:prepared.values())if(value.bytes()!=null)total+=value.bytes().length;
            if(!VisionImageStorage.quotaAllows(root,total))return status("NOT_SAVED","STORAGE_QUOTA_EXCEEDED");
            for(var entry:prepared.entrySet())if(entry.getValue().bytes()!=null) {
                Path imagePath=VisionImageStorage.path(folder,recordId,entry.getKey(),String.valueOf(entry.getValue().metadata().get("mimeType")));
                // CREATE_NEW must succeed before this path becomes ours to clean up.
                try(var output=Files.newOutputStream(imagePath,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {created.add(imagePath);output.write(entry.getValue().bytes());}
            }
            pending=Files.createTempFile(folder,"pending-",".tmp");
            Files.write(pending,bytes,StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(pending,folder.resolve(recordId+".json"),StandardCopyOption.ATOMIC_MOVE);
            committed=true;
            return Map.of("status","SAVED","recordId",recordId,"inspectionId",String.valueOf(inspectionId),"mock",mock,"imageRetention",record.get("imageRetention"));
        } catch(Exception ex) {
            return status("NOT_SAVED",ex instanceof IllegalArgumentException?"INVALID_MODEL_RESULT":"ARCHIVE_UNAVAILABLE");
        } finally {
            if(pending!=null)try {Files.deleteIfExists(pending);}catch(IOException ignored){}
            if(!committed)for(Path path:created)try {Files.deleteIfExists(path);}catch(IOException ignored){}
        }
    }
    /** Call only with IDs already selected under the current authorized business scope. */
    public List<Map<String,Object>> snapshotRows(Set<Long> inspectionIds) {
        List<Map<String,Object>> rows=new ArrayList<>(); int records=0,readBytes=0;
        for(Long id:new TreeSet<>(inspectionIds)) {
            if(id==null || id<=0) continue;
            Set<String> issues=new TreeSet<>(); int found=0;StringBuilder metadata=new StringBuilder();
            try {
                Path folder=folder(id);
                checkExistingAncestors(folder);
                if(Files.exists(folder,LinkOption.NOFOLLOW_LINKS)) {
                    checkPath(root); checkPath(folder);
                    fingerprintImageDirectory(folder,metadata,issues);
                    List<Path> files;
                    try(var stream=Files.list(folder)) { files=stream.filter(p->p.getFileName().toString().endsWith(".json")).limit(MAX_PER_INSPECTION+1L).sorted().toList(); }
                    if(files.size()>MAX_PER_INSPECTION) issues.add("RECORD_LIMIT_REACHED");
                    for(Path file:files.stream().limit(MAX_PER_INSPECTION).toList()) {
                        try {
                            checkPath(file); long size=Files.size(file);
                            // Even skipped bodies change snapshot provenance when their metadata changes.
                            metadata.append(file.getFileName()).append(':').append(size).append(':').append(Files.getLastModifiedTime(file)).append(';');
                            if(records>=MAX_RECORDS) {issues.add("TOTAL_RECORD_LIMIT_REACHED");continue;}
                            if(size>MAX_BYTES) { issues.add("RECORD_SIZE_LIMIT");continue; }
                            if(size>MAX_READ_BYTES-readBytes) {issues.add("ARCHIVE_READ_BYTE_LIMIT");continue;}
                            String name=file.getFileName().toString();
                            if(!name.matches("[0-9a-f-]{36}\\.json")) {issues.add("CORRUPT_RECORD");continue;}
                            byte[] bytes;
                            try(var input=Files.newInputStream(file)) {bytes=input.readNBytes(MAX_READ_BYTES-readBytes);}
                            readBytes+=bytes.length;
                            if(bytes.length!=size) {issues.add("RECORD_CHANGED_DURING_READ");continue;}
                            JsonNode record=JSON.readTree(bytes);
                            if(!validRecord(record,id,name.substring(0,36))) {issues.add("CORRUPT_RECORD");continue;}
                            if(record.path("schemaVersion").asInt()==2)inspectImageFiles(folder,record,metadata,issues);
                            rows.add(Map.of("record_type","RECORD","inspection_id",String.valueOf(id),"record_json",JSON.writeValueAsString(record)));
                            records++; found++;
                        } catch(Exception ex) {issues.add("CORRUPT_RECORD");}
                    }
                }
            } catch(Exception ex) {issues.add("ARCHIVE_UNAVAILABLE");}
            rows.add(Map.of("record_type","STATUS","inspection_id",String.valueOf(id),"archive_status",issues.isEmpty()?(found>0?"RECORDED":"SOURCE_RECORD_MISSING"):"PARTIAL","limitations",String.join(",",issues),"file_metadata_fingerprint",metadataFingerprint(metadata.toString())));
        }
        return rows;
    }
    private void fingerprintImageDirectory(Path folder,StringBuilder fingerprint,Set<String> issues) throws IOException {
        List<Path> files;
        try(var stream=Files.list(folder)) {
            files=stream.filter(p->p.getFileName().toString().matches("[0-9a-f-]{36}-(original|input|annotated)\\.(png|jpg)"))
                    .limit(MAX_PER_INSPECTION*3L+1).sorted().toList();
        }
        if(files.size()>MAX_PER_INSPECTION*3)issues.add("IMAGE_FILE_LIMIT_REACHED");
        for(Path file:files) {
            fingerprint.append(file.getFileName()).append(':');
            try {
                checkPath(file);
                var attributes=Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                fingerprint.append(attributes.size()).append(':').append(attributes.lastModifiedTime()).append(':').append(attributes.fileKey()).append(';');
            }catch(IOException invalid){fingerprint.append("INVALID;");issues.add("IMAGE_FILE_INVALID");}
        }
    }
    public record ImageAsset(byte[] bytes,String mimeType,String sha256) {}
    public ImageAsset readImage(long inspectionId,String recordId,String variant) throws IOException {
        try {
            Path folder=folder(inspectionId);
            // Validate references before resolving a manifest or opening any image.
            VisionImageStorage.path(folder,recordId,variant,"image/png");
            checkExistingAncestors(folder);checkPath(root);checkPath(folder);
            Path manifest=folder.resolve(recordId+".json");checkPath(manifest);
            if(Files.size(manifest)>MAX_BYTES)throw new IOException("Invalid manifest size");
            byte[] manifestBytes;try(var input=Files.newInputStream(manifest)){manifestBytes=input.readNBytes(MAX_BYTES+1);}
            if(manifestBytes.length>MAX_BYTES)throw new IOException("Invalid manifest size");
            JsonNode record=JSON.readTree(manifestBytes);
            if(!validRecord(record,inspectionId,recordId) || record.path("schemaVersion").asInt()!=2)throw new IOException("Invalid image manifest");
            JsonNode image=record.path("images").path(variant);
            if(!image.path("status").asText().equals("AVAILABLE"))throw new IOException("Image unavailable");
            Path file=VisionImageStorage.path(folder,recordId,variant,image.path("mimeType").asText());checkPath(file);
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)!=image.path("byteSize").asLong())throw new IOException("Image changed");
            byte[] bytes;try(var input=Files.newInputStream(file)){bytes=input.readNBytes(VisionImageStorage.MAX_IMAGE_BYTES+1);}
            if(bytes.length!=image.path("byteSize").asLong() || !VisionImageStorage.sha256(bytes).equals(image.path("sha256").asText()))throw new IOException("Image hash mismatch");
            var validated=VisionImageStorage.validate(bytes,"ARCHIVE_READ");
            for(String field:List.of("mimeType","width","height"))if(!String.valueOf(validated.metadata().get(field)).equals(image.path(field).asText()))throw new IOException("Image metadata mismatch");
            return new ImageAsset(bytes,image.path("mimeType").asText(),image.path("sha256").asText());
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid image reference",invalid);}
    }
    /** File metadata participates in final revalidation without loading image bodies into every query. */
    private void inspectImageFiles(Path folder,JsonNode record,StringBuilder fingerprint,Set<String> issues) {
        for(String variant:VisionImageStorage.VARIANTS) {
            JsonNode image=record.path("images").path(variant);
            if(!"AVAILABLE".equals(image.path("status").asText()))continue;
            String problem=null;
            fingerprint.append(record.path("recordId").asText()).append(':').append(variant).append(':');
            try {
                Path file=VisionImageStorage.path(folder,record.path("recordId").asText(),variant,image.path("mimeType").asText());
                if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)) {problem="IMAGE_FILE_MISSING";fingerprint.append("MISSING;");}
                else {
                    checkPath(file);
                    var attributes=Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    fingerprint.append(attributes.size()).append(':').append(attributes.lastModifiedTime()).append(':').append(attributes.fileKey()).append(';');
                    if(!attributes.isRegularFile() || attributes.size()!=image.path("byteSize").asLong())problem="IMAGE_FILE_CHANGED";
                }
            }catch(IOException unsafe){problem="IMAGE_FILE_INVALID";fingerprint.append("INVALID;");}
            if(problem!=null) {
                issues.add(problem);
                var updated=(com.fasterxml.jackson.databind.node.ObjectNode)image;updated.put("status",problem.equals("IMAGE_FILE_MISSING")?"MISSING":"INVALID");updated.put("reason",problem);
                ((com.fasterxml.jackson.databind.node.ObjectNode)record).put("imageRetention","PARTIAL_IMAGES");
            }
        }
    }
    private Path folder(long id) { if(id<=0)throw new IllegalArgumentException(); return root.resolve(Long.toString(id)); }
    private void checkExistingAncestors(Path path) throws IOException {
        for(Path ancestor=path;ancestor!=null;ancestor=ancestor.getParent()) {
            if(Files.exists(ancestor,LinkOption.NOFOLLOW_LINKS) && (Files.isSymbolicLink(ancestor)||!ancestor.toRealPath().equals(ancestor.toAbsolutePath().normalize()))) throw new IOException("Unsafe archive ancestor");
        }
    }
    private void checkPath(Path path) throws IOException {
        if(!path.startsWith(root)||Files.isSymbolicLink(path)||!path.toRealPath().equals(path.toAbsolutePath().normalize())) throw new IOException("Unsafe archive path");
    }
    private static boolean validRecord(JsonNode r,long id,String recordId) {
        if(r==null||!Set.of(1,2).contains(r.path("schemaVersion").asInt())||!r.path("recordId").asText().equals(recordId)||!r.path("inspectionId").asText().equals(String.valueOf(id))||!r.path("source").asText().equals("SERVER_YOLO_RESPONSE")||!r.path("imageSha256").asText().matches("[0-9a-f]{64}")||!r.path("mock").isBoolean()||!r.path("defect").isBoolean()||!r.path("count").canConvertToInt()||r.path("count").asInt()<0||!r.path("detections").isArray()||r.path("detections").size()>200) return false;
        if(r.path("schemaVersion").asInt()==1) {
            if(!r.path("imageRetention").asText().equals("IMAGE_NOT_RETAINED") || r.has("images"))return false;
        } else {
            if(!r.path("bindingSource").asText().equals("INSPECTION_RECORD_AT_DETECTION") || !VisionImageStorage.validMetadata(r.path("images")))return false;
            boolean all=true;for(String variant:VisionImageStorage.VARIANTS)all&="AVAILABLE".equals(r.path("images").path(variant).path("status").asText());
            if(!r.path("imageRetention").asText().equals(all?"IMAGES_RETAINED":"PARTIAL_IMAGES"))return false;
            if(!r.path("imageSha256").asText().equals(r.path("images").path("input").path("sha256").asText()))return false;
        }
        try {Instant.parse(r.path("recordedAt").asText());}catch(Exception e){return false;}
        if(!Set.of("DECLARED_MOCK_GENERATOR","SERVER_FIELDS_VALIDATED_BEFORE_NORMALIZATION").contains(r.path("rawCoreProvenance").asText()))return false;
        double confidence=r.path("maxConfidence").asDouble(Double.NaN); if(!Double.isFinite(confidence)||confidence<0||confidence>1)return false;
        for(JsonNode d:r.path("detections")) {
            double c=d.path("confidence").asDouble(Double.NaN); if(!Double.isFinite(c)||c<0||c>1||!d.path("box").isArray()||d.path("box").size()!=4)return false;
            for(JsonNode coordinate:d.path("box")) if(!coordinate.isNumber()||!Double.isFinite(coordinate.asDouble()))return false;
        }
        var names=r.fieldNames();while(names.hasNext())if(!RECORD_FIELDS.contains(names.next()))return false;
        return true;
    }
    private static String bounded(Object value,int max) {String text=value==null?"":String.valueOf(value);return text.substring(0,Math.min(text.length(),max));}
    private static String metadataFingerprint(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    private static Map<String,Object> status(String status,String reason) {return Map.of("status",status,"reason",reason);}
}

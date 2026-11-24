package com.upc.computer.controller;

import com.upc.computer.common.BusinessException;
import java.io.ByteArrayInputStream;
import java.util.*;
import javax.imageio.ImageIO;

/** Short-lived originals and inference receipts, isolated by account and conversation. */
final class ChatImageMemory {
    record Photo(long owner,String session,byte[] bytes,String format,String workRun,Integer count,
                 List<String> locations,byte[] frame,long expires) {
        int size(){return bytes.length+frame.length;}
    }
    private final Map<String,Photo> photos=new LinkedHashMap<>();
    private int retainedBytes;

    synchronized String save(long owner,String session,byte[] bytes,String format,String workRun,Map<String,Object> yolo){
        long now=System.currentTimeMillis();
        var entries=photos.values().iterator();
        while(entries.hasNext()){
            Photo p=entries.next();
            if(p.expires()<=now){retainedBytes-=p.size();entries.remove();}
        }
        entries=photos.values().iterator();
        var frame=frame(yolo);
        while(entries.hasNext()&&(retainedBytes+bytes.length+frame.length>64*1024*1024||photos.size()>=128)){
            retainedBytes-=entries.next().size();entries.remove();
        }
        var id=UUID.randomUUID().toString();
        Integer count=yolo!=null && yolo.get("count") instanceof Number n?n.intValue():null;
        photos.put(id,new Photo(owner,session,bytes,format,workRun,count,locations(yolo,frame),frame,now+3600_000));
        retainedBytes+=bytes.length+frame.length;
        return id;
    }

    synchronized Photo read(long owner,String session,String ref){
        Photo p=photos.get(ref);
        if(p==null||p.owner()!=owner||!p.session().equals(session)||p.expires()<=System.currentTimeMillis())
            throw new BusinessException(404,"当前会话的图片已过期或不可用，请重新上传。");
        return p;
    }

    static byte[] frame(Map<String,Object> result){
        if(result!=null && result.get("resultImage") instanceof String marked && marked.length()<8_000_000
                && marked.startsWith("data:image/jpeg;base64,")){
            try{return Base64.getDecoder().decode(marked.substring(marked.indexOf(',')+1));}
            catch(IllegalArgumentException invalid){return new byte[0];}
        }
        return new byte[0];
    }
    static List<String> locations(Map<String,Object> result,byte[] frame){
        if(result==null||!(result.get("detections") instanceof List<?> detections)||detections.isEmpty()||frame.length==0)return List.of();
        // The detector resizes the original. xyxy is in the annotated frame's pixel space.
        try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(frame))){
            var readers=ImageIO.getImageReaders(stream);
            if(!readers.hasNext())return List.of();
            var reader=readers.next();
            int w,h;
            try{reader.setInput(stream);w=reader.getWidth(0);h=reader.getHeight(0);}finally{reader.dispose();}
            List<String> lines=new ArrayList<>();
            for(Object value:detections){
                if(!(value instanceof Map<?,?> row)||!(row.get("box") instanceof List<?> box)||box.size()!=4)return List.of();
                double x1=((Number)box.get(0)).doubleValue()/w,y1=((Number)box.get(1)).doubleValue()/h;
                double x2=((Number)box.get(2)).doubleValue()/w,y2=((Number)box.get(3)).doubleValue()/h;
                if(!(0<=x1&&x1<x2&&x2<=1&&0<=y1&&y1<y2&&y2<=1))return List.of();
                double x=(x1+x2)/2,y=(y1+y2)/2;
                String region=(x<1./3?"左":x>2./3?"右":"中")+(y<1./3?"上":y>2./3?"下":"部");
                lines.add(String.format(Locale.ROOT,"第 %d 处框中心在%s，检测框横向 %.1f%%–%.1f%%、纵向 %.1f%%–%.1f%%。",lines.size()+1,region,x1*100,x2*100,y1*100,y2*100));
            }
            return List.copyOf(lines);
        }catch(Exception invalid){return List.of();}
    }
}

package com.upc.computer.assistant.v2.orders;

import com.upc.computer.common.BusinessException;
import com.upc.computer.entity.Material;
import com.upc.computer.mapper.MaterialMapper;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Component
public class OrderProductCatalog {
    private final MaterialMapper materials;
    public OrderProductCatalog(MaterialMapper materials){this.materials=materials;}
    public List<Map<String,Object>> products() {
        return materials.materialList().stream().filter(this::available).sorted(Comparator.comparing(Material::getMaterialId)).map(this::view).toList();
    }
    public Material require(Object id) {
        long key;
        try {key=Long.parseLong(Objects.toString(id,""));}catch(NumberFormatException ex){throw new BusinessException(400,"请选择有效产品");}
        Material material=materials.getMaterialById(key);if(available(material))return material;
        throw new BusinessException(409,"所选产品已不存在或停用，请重新选择产品");
    }
    private boolean available(Material m){return m!=null&&"FINISHED".equals(m.getMaterialType())&&Integer.valueOf(1).equals(m.getStatus());}
    public Material requireForUpdate(Object id) {
        Material material=materials.getMaterialForOrderUpdate(Long.valueOf(id.toString()));
        if(!available(material))throw new BusinessException(409,"所选产品已不存在或停用，请重新选择产品");return material;
    }
    public Map<String,Object> view(Material m){return Map.of("materialId",m.getMaterialId(),"materialCode",Objects.toString(m.getMaterialCode(),""),"materialName",Objects.toString(m.getMaterialName(),""),"specification",Objects.toString(m.getSpecification(),""),"unit",Objects.toString(m.getUnit(),"台"));}
    public String fingerprint(Material m){return hash(String.join("|",Objects.toString(m.getMaterialId(),""),Objects.toString(m.getMaterialCode(),""),Objects.toString(m.getMaterialName(),""),Objects.toString(m.getSpecification(),""),Objects.toString(m.getUnit(),""),Objects.toString(m.getStandardCost(),""),Objects.toString(m.getStatus(),"")));}
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}

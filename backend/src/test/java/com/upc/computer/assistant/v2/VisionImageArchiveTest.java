package com.upc.computer.assistant.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.upc.computer.assistant.v2.vision.VisionEvidenceArchive;
import com.upc.computer.assistant.v2.vision.QualityEvidenceDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@ResourceLock("assistant.vision.max-storage-bytes")
class VisionImageArchiveTest {
    @TempDir Path dir;
    final ObjectMapper json=new ObjectMapper();
    VisionEvidenceArchive archive() {return new VisionEvidenceArchive(dir,Clock.systemUTC());}
    byte[] png() throws IOException {return png(3,2);}
    byte[] png(int width,int height) throws IOException {var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();}
    Map<String,Object> result(byte[] annotation) {var result=new HashMap<String,Object>();result.put("rawResultValid",true);if(annotation!=null)result.put("resultImage","data:image/png;base64,"+Base64.getEncoder().encodeToString(annotation));return result;}
    Map<String,Object> save(byte[] input,byte[] original,Map<String,Object> result) {
        return archive().save(7L,input,original,result,Map.of("inspectionNo","QI-7","batchNo","B-3","workOrderId",22L,"materialId",12L));
    }
    JsonNode record(Map<String,Object> saved) throws IOException {return json.readTree(dir.resolve("7").resolve(saved.get("recordId")+".json").toFile());}
    VisionEvidenceArchive.ImageAsset image(String id,String variant) throws IOException {return archive().readImage(7L,id,variant);}
    byte[] bytes(VisionEvidenceArchive.ImageAsset asset){return asset.bytes();}
    Path imageFile(String id,String variant) throws IOException {try(var files=Files.walk(dir)){return files.filter(Files::isRegularFile).filter(p->p.getFileName().toString().equals(id+"-"+variant+".png")).findFirst().orElseThrow();}}
    @Test void retainsThreeRealImagesAndServerBindingWithoutBase64OrLocalPaths() throws Exception {
        byte[] png=png(),original=png(6,4),annotated=png(1,1);var saved=save(png,original,result(annotated));assertThat(saved).containsEntry("status","SAVED").containsEntry("imageRetention","IMAGES_RETAINED");
        JsonNode r=record(saved);assertThat(r.path("schemaVersion").asInt()).isEqualTo(2);assertThat(r.path("inspectionNo").asText()).isEqualTo("QI-7");assertThat(r.path("batchNo").asText()).isEqualTo("B-3");assertThat(r.path("workOrderId").asLong()).isEqualTo(22);assertThat(r.path("materialId").asLong()).isEqualTo(12);assertThat(r.path("bindingSource").asText()).isEqualTo("INSPECTION_RECORD_AT_DETECTION");
        for(String variant:List.of("original","input","annotated")){assertThat(r.path("images").path(variant).path("status").asText()).isEqualTo("AVAILABLE");assertThat(bytes(image((String)saved.get("recordId"),variant))).isEqualTo(switch(variant){case "original"->original;case "annotated"->annotated;default->png;});}
        assertThat(r.toString()).doesNotContain("base64",dir.toString(),"resultImage");
    }
    @Test void absentOriginalAndAnnotationAreExplicitAndInputIsNeverRelabeled() throws Exception {
        var saved=save(png(),null,result(null));JsonNode r=record(saved);assertThat(r.path("images").path("original").path("status").asText()).isEqualTo("MISSING");assertThat(r.path("images").path("annotated").path("status").asText()).isEqualTo("MISSING");assertThat(saved).containsEntry("imageRetention","PARTIAL_IMAGES");
        assertThatThrownBy(()->image((String)saved.get("recordId"),"original")).isInstanceOf(IOException.class);
    }
    @Test void rejectsInvalidInputAndMarksInvalidOptionalImages() throws Exception {
        assertThat(save(new byte[]{1,2,3},null,result(null))).containsEntry("status","NOT_SAVED");
        var saved=save(png(),new byte[]{1,2,3},result(new byte[]{1,2,3}));JsonNode r=record(saved);assertThat(r.path("images").path("original").path("status").asText()).isEqualTo("INVALID");assertThat(r.path("images").path("annotated").path("status").asText()).isEqualTo("INVALID");
    }
    @Test void quotaFailureLeavesExistingEvidenceAndNoPartialManifest() throws Exception {
        var saved=save(png(),null,result(null));String before=Files.readString(dir.resolve("7").resolve(saved.get("recordId")+".json"));
        System.setProperty("assistant.vision.max-storage-bytes","1");try{assertThat(save(png(),png(),result(png()))).containsEntry("status","NOT_SAVED").containsEntry("reason","STORAGE_QUOTA_EXCEEDED");}finally{System.clearProperty("assistant.vision.max-storage-bytes");}
        assertThat(Files.readString(dir.resolve("7").resolve(saved.get("recordId")+".json"))).isEqualTo(before);try(var paths=Files.list(dir.resolve("7"))){assertThat(paths.filter(p->p.toString().endsWith(".json")).count()).isEqualTo(1);}
    }
    @Test void missingAndChangedImagesInvalidateSnapshotAndCannotBeRead() throws Exception {
        var saved=save(png(),png(),result(png()));String id=(String)saved.get("recordId");var before=archive().snapshotRows(Set.of(7L));Path input=imageFile(id,"input");Files.write(input,new byte[]{1,2,3});
        assertThat(archive().snapshotRows(Set.of(7L))).isNotEqualTo(before);assertThatThrownBy(()->image(id,"input")).isInstanceOf(IOException.class);Files.delete(imageFile(id,"original"));assertThat(archive().snapshotRows(Set.of(7L)).toString()).contains("IMAGE_FILE_MISSING");assertThatThrownBy(()->image(id,"original")).isInstanceOf(IOException.class);
    }
    @Test void refusesPathTraversalAndManifestOwnershipTampering() throws Exception {
        var saved=save(png(),null,result(null));String id=(String)saved.get("recordId");assertThatThrownBy(()->image(id,"../original")).isInstanceOf(IOException.class);assertThatThrownBy(()->image("../"+id,"input")).isInstanceOf(IOException.class);
        var manifest=(com.fasterxml.jackson.databind.node.ObjectNode)record(saved);manifest.put("inspectionId","8");json.writeValue(dir.resolve("7").resolve(id+".json").toFile(),manifest);assertThatThrownBy(()->image(id,"input")).isInstanceOf(IOException.class);
    }
    @Test void legacyMetadataStillLoadsAndImageRetainedLimitationsFollowActualRecords() throws Exception {
        assertThat(archive().save(9,new byte[]{1,2,3},result(null))).containsEntry("status","SAVED");assertThat(archive().snapshotRows(Set.of(9L)).toString()).contains("IMAGE_NOT_RETAINED");
        save(png(),png(),result(png()));var rows=new ArrayList<Map<String,String>>();for(var row:archive().snapshotRows(Set.of(7L))){var strings=new HashMap<String,String>();row.forEach((k,v)->strings.put(k,String.valueOf(v)));rows.add(strings);}
        var details=QualityEvidenceDetails.vision(Map.of("vision_evidence_archive",rows),List.of(Map.of("inspection_id",7L)),List.of());assertThat(details.get("limitations").toString()).doesNotContain("IMAGE_NOT_RETAINED");
    }
    @Test void sameSizeHashTamperingAndMimeSpoofingCannotBeServed() throws Exception {
        var saved=save(png(),null,result(null));String id=(String)saved.get("recordId");Path input=imageFile(id,"input");byte[] tampered=Files.readAllBytes(input);tampered[tampered.length-1]^=1;Files.write(input,tampered);
        assertThatThrownBy(()->image(id,"input")).isInstanceOf(IOException.class).hasMessageContaining("hash");
        var spoof=result(png());spoof.put("resultImage","data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(png()));assertThat(record(save(png(),null,spoof)).path("images").path("annotated").path("status").asText()).isEqualTo("INVALID");
    }
    @Test void jpegIsServedWithActualMimeAndDimensionMetadata() throws Exception {
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(4,5,BufferedImage.TYPE_INT_RGB),"jpg",out);byte[] jpeg=out.toByteArray();
        var saved=save(jpeg,jpeg,result(jpeg));var asset=image((String)saved.get("recordId"),"input");assertThat(asset.mimeType()).isEqualTo("image/jpeg");assertThat(asset.bytes()).isEqualTo(jpeg);assertThat(record(saved).path("images").path("input").path("width").asInt()).isEqualTo(4);
    }
    @Test void rejectsOversizedBytesAndPixelBombBeforeFullDecode() throws Exception {
        assertThat(save(Arrays.copyOf(png(),10*1024*1024+1),null,result(null))).containsEntry("status","NOT_SAVED");
        assertThat(save(Arrays.copyOf(png(),33),null,result(null))).containsEntry("status","NOT_SAVED");
        byte[] bomb=png();java.nio.ByteBuffer.wrap(bomb,16,4).putInt(40001);java.nio.ByteBuffer.wrap(bomb,20,4).putInt(1000);var crc=new java.util.zip.CRC32();crc.update(bomb,12,17);java.nio.ByteBuffer.wrap(bomb,29,4).putInt((int)crc.getValue());
        var saved=save(png(),bomb,result(bomb));assertThat(record(saved).path("images").path("original").path("status").asText()).isEqualTo("INVALID");assertThat(record(saved).path("images").path("annotated").path("status").asText()).isEqualTo("INVALID");
    }
    @Test void concurrentSavesCannotExceedStorageBudget() throws Exception {
        var probe=new VisionEvidenceArchive(dir.resolve("probe"),Clock.systemUTC());assertThat(probe.save(7,png(),null,result(null),Map.of())).containsEntry("status","SAVED");long required;try(var paths=Files.walk(dir.resolve("probe"))){required=paths.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(IOException ex){throw new UncheckedIOException(ex);}}).sum();}
        System.setProperty("assistant.vision.max-storage-bytes",String.valueOf(required+100));var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {byte[] png=png();var shared=new VisionEvidenceArchive(dir.resolve("concurrent"),Clock.systemUTC());var tasks=new ArrayList<java.util.concurrent.Callable<Map<String,Object>>>();for(int i=0;i<8;i++)tasks.add(()->shared.save(7,png,null,result(null),Map.of()));var outcomes=pool.invokeAll(tasks);int saved=0;for(var task:outcomes){var result=task.get();if("SAVED".equals(result.get("status")))saved++;else assertThat(result).containsEntry("reason","STORAGE_QUOTA_EXCEEDED");}assertThat(saved).isEqualTo(1);
        }finally {pool.shutdownNow();System.clearProperty("assistant.vision.max-storage-bytes");}
    }
    @Test void imageMetadataStillChangesFingerprintWhenItsManifestIsSkippedByReadBudget() throws Exception {
        var saved=save(png(),null,result(null));String id=(String)saved.get("recordId");Path manifest=dir.resolve("7").resolve(id+".json");Files.writeString(manifest,Files.readString(manifest)+" ".repeat(70000));var before=archive().snapshotRows(Set.of(7L));assertThat(before.toString()).contains("ARCHIVE_READ_BYTE_LIMIT");
        Files.delete(imageFile(id,"input"));assertThat(archive().snapshotRows(Set.of(7L))).isNotEqualTo(before);
    }
    @Test void imageModificationTimeInvalidatesSnapshotEvenWhenBytesAreUnchanged() throws Exception {
        var saved=save(png(),null,result(null));Path input=imageFile((String)saved.get("recordId"),"input");var before=archive().snapshotRows(Set.of(7L));Files.setLastModifiedTime(input,java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(input).toMillis()+5000));assertThat(archive().snapshotRows(Set.of(7L))).isNotEqualTo(before);
    }
    @Test void rejectsSymlinkImagesAndArchiveDirectoriesWhenFilesystemSupportsThem() throws Exception {
        var saved=save(png(),null,result(null));String id=(String)saved.get("recordId");Path input=imageFile(id,"input"),outside=dir.resolve("outside.png");Files.write(outside,png());Files.delete(input);
        try {Files.createSymbolicLink(input,outside);}catch(IOException | UnsupportedOperationException denied){org.junit.jupiter.api.Assumptions.assumeTrue(false,"Filesystem does not permit symbolic links");}
        assertThatThrownBy(()->image(id,"input")).isInstanceOf(IOException.class);assertThat(archive().snapshotRows(Set.of(7L)).toString()).contains("IMAGE_FILE_INVALID");
        Path linked=dir.resolve("linked");Files.createSymbolicLink(linked,dir.resolve("7"));assertThat(new VisionEvidenceArchive(linked,Clock.systemUTC()).save(9,png(),null,result(null),Map.of())).containsEntry("status","NOT_SAVED");
    }

}


package com.upc.computer.assistant.v2.tools.delivery;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Reconciled recorded stock and explicit planning scenarios; never creates an inventory reservation. */
public final class DeliveryReadinessCalculator {
    private DeliveryReadinessCalculator() {}
    private static final BigDecimal ZERO=BigDecimal.ZERO;

    public static List<Map<String,Object>> fulfillment(Map<String,List<Map<String,String>>> tables, boolean complete) {
        var result=new DeliveryQuantityCalculator().fulfillment(rows(tables,"customer_order_item"),rows(tables,"delivery_order"),complete);
        Map<String,BigDecimal> pools=new HashMap<>();Set<String> unknown=new HashSet<>();
        for(var line:result) {
            String material=(String)line.get("materialId");
            if(pools.containsKey(material)||unknown.contains(material))continue;
            BigDecimal pool=ZERO;
            for(var stock:rows(tables,"inventory")) {
                if(!Objects.equals(material,stock.get("material_id")))continue;
                BigDecimal available=available(stock);
                if(available==null){unknown.add(material);continue;}
                if(available.signum()==0)continue;
                if(!eligibleLot(stock,tables)){unknown.add(material);continue;}
                pool=pool.add(available);
            }
            pools.put(material,pool);
        }
        // Stable order is significant: these are competing claims on one shared pool, not independent estimates.
        result.sort(Comparator.comparing(r -> new java.math.BigInteger((String)r.get("orderItemPk"))));
        for(var line:result) {
            String material=(String)line.get("materialId");
            BigDecimal remaining=number((String)line.get("remainingDeliveryQty"));
            boolean valid=complete&&!unknown.contains(material)&&remaining!=null;
            var source=rows(tables,"customer_order_item").stream().filter(r -> Objects.equals(r.get("order_item_id"),line.get("orderItemPk"))).findFirst().orElse(Map.of());
            valid &= source.get("unit")!=null&&Objects.equals(source.get("unit"),source.get("material_unit"));
            BigDecimal pool=pools.getOrDefault(material,ZERO);
            BigDecimal candidate=valid?pool.min(remaining):null;
            if(candidate!=null)pools.put(material,pool.subtract(candidate));
            line.put("candidateReadyQty",text(candidate));
            line.put("scenarioReadyGapQty",candidate==null?null:text(remaining.subtract(candidate).max(ZERO)));
            line.put("allocationStatus",candidate==null?"UNVERIFIED_LOT_EVIDENCE":"SCENARIO_NOT_RESERVED");
            line.put("readyQuantityBasis","RECONCILED_FULL_INSPECTION_POOL_SCENARIO");
            line.put("readyEvidenceLimitations",List.of("NO_ORDER_RESERVATION_DETAIL","SHARED_POOL_SCENARIO_NOT_A_SHIPPING_AUTHORIZATION"));
            // Only zero can be confirmed without an order-specific reservation: no remaining demand or no recorded stock.
            boolean noRecordedStock=rows(tables,"inventory").stream().filter(s -> Objects.equals(material,s.get("material_id")))
                    .allMatch(s -> number(s.get("quantity_on_hand"))!=null&&number(s.get("quantity_on_hand" )).signum()==0);
            if(valid&&(remaining.signum()==0||noRecordedStock))line.put("shippableQty","0");
        }
        return result;
    }

    private static boolean eligibleLot(Map<String,String> stock,Map<String,List<Map<String,String>>> tables) {
        String batch=stock.get("batch_no"), material=stock.get("material_id"), inventory=stock.get("inventory_id");
        if(batch==null||batch.isBlank()||inventory==null)return false;
        var lineage=stockTransactions(tables);
        var txs=lineage.stream().filter(t -> inventory.equals(t.get("inventory_id"))).toList();
        if(txs.isEmpty()||txs.stream().map(t -> t.get("transaction_id")).anyMatch(Objects::isNull)||
                txs.stream().map(t -> t.get("transaction_id")).distinct().count()!=txs.size())return false;
        BigDecimal balance=ZERO;Map<String,BigDecimal> inboundByWork=new HashMap<>();
        for(var t:txs) {
            BigDecimal n=number(t.get("quantity"));
            if(n==null||!batch.equals(t.get("batch_no"))||!Objects.equals(material,t.get("material_id")))return false;
            if("SALE_OUT".equals(t.get("transaction_type"))){balance=balance.subtract(n);continue;}
            if(!"PRODUCT_IN".equals(t.get("transaction_type")))return false;
            String work=t.get("related_work_order_id");
            if(work==null||rows(tables,"work_order").stream().noneMatch(w -> work.equals(w.get("work_order_id"))&&material.equals(w.get("material_id"))&&"LINKED".equals(w.get("mapping_status"))))return false;
            inboundByWork.merge(work,n,BigDecimal::add);balance=balance.add(n);
        }
        if(inboundByWork.isEmpty()||number(stock.get("quantity_on_hand"))==null||balance.compareTo(number(stock.get("quantity_on_hand")))!=0)return false;
        for(var entry:inboundByWork.entrySet()) {
            // Count every receipt for this work/material/batch across stock locations; never reuse one sample for each location.
            BigDecimal lotIn=ZERO;
            for(var t:lineage)if(entry.getKey().equals(t.get("related_work_order_id"))&&material.equals(t.get("material_id"))&&batch.equals(t.get("batch_no"))&&"PRODUCT_IN".equals(t.get("transaction_type"))) {
                BigDecimal n=number(t.get("quantity"));if(n==null)return false;lotIn=lotIn.add(n);
            }
            var inspections=rows(tables,"quality_inspection").stream().filter(q -> entry.getKey().equals(q.get("work_order_id"))&&material.equals(q.get("material_id"))&&batch.equals(q.get("batch_no"))&&Set.of("FINAL","RECHECK").contains(q.getOrDefault("inspection_type",""))).toList();
            if(inspections.size()!=1)return false;
            var q=inspections.get(0);
            if("CONTRADICTORY".equals(q.get("relation_status")))return false;
            if(!"FINAL".equals(q.get("inspection_type"))||!"PASSED".equals(q.get("inspection_status"))||!"QUALIFIED".equals(q.get("inspection_result")))return false;
            BigDecimal sample=number(q.get("sample_quantity")),qualified=number(q.get("qualified_quantity")),bad=number(q.get("unqualified_quantity"));
            if(sample==null||qualified==null||bad==null||bad.signum()!=0||sample.compareTo(lotIn)!=0||qualified.compareTo(lotIn)!=0)return false;
            if(rows(tables,"nonconforming_product").stream().anyMatch(d -> defectAffects(d,q,entry.getKey(),material,batch,tables)))return false;
        }
        return true;
    }

    public static List<Map<String,Object>> materials(Map<String,List<Map<String,String>>> tables, boolean complete,LocalDate day) {
        Map<String,BigDecimal> demands=new TreeMap<>();Set<String> invalid=new HashSet<>();Map<String,String> units=new HashMap<>();
        for(var b:rows(tables,"bom")) {
            String child=b.get("child_material_id");if(child==null)continue;
            demands.putIfAbsent(child,ZERO);
            String unit=b.get("child_unit");if(unit==null||units.containsKey(child)&&!unit.equals(units.get(child)))invalid.add(child);else units.put(child,unit);
        }
        Set<String> works=new HashSet<>(),activeWorks=new HashSet<>();boolean valid=complete;
        Map<String,Map<String,String>> grossByWork=new LinkedHashMap<>();
        for(var w:rows(tables,"work_order")) {
            if(!"LINKED".equals(w.get("mapping_status"))||!works.add(w.get("work_order_id"))){valid=false;continue;}
            if(Set.of("COMPLETED","CANCELLED").contains(w.getOrDefault("status","")))continue;
            activeWorks.add(w.get("work_order_id"));
            var gross=DeliveryProductionCalculator.gross(w,rows(tables,"bom"),day);
            grossByWork.put(w.get("work_order_id"),gross);
            if(gross.isEmpty())valid=false;
            gross.forEach((m,q)->demands.merge(m,number(q),BigDecimal::add));
        }
        if(works.isEmpty())valid=false;
        // Reuse the same finished-goods allocation before allowing the scenario to consume a material pool.
        Map<String,BigDecimal> finishedAllocated=new HashMap<>();
        for(var line:fulfillment(tables,complete)) {
            BigDecimal n=number((String)line.get("candidateReadyQty"));
            if(n!=null)finishedAllocated.merge((String)line.get("materialId"),n,BigDecimal::add);
            else invalid.add((String)line.get("materialId"));
        }
        var result=new ArrayList<Map<String,Object>>();
        for(var demand:demands.entrySet()) {
            String material=demand.getKey();boolean known=valid&&!invalid.contains(material);BigDecimal issued=ZERO,pool=ZERO;
            boolean issuedKnown=complete;Map<String,BigDecimal> issuedByWork=new HashMap<>();
            for(var tx:rows(tables,"inventory_transaction"))if(material.equals(tx.get("material_id"))&&activeWorks.contains(tx.get("related_work_order_id"))&&"MATERIAL_OUT".equals(tx.get("transaction_type"))) {
                BigDecimal n=number(tx.get("quantity"));if(n==null){known=false;issuedKnown=false;}else {issued=issued.add(n);issuedByWork.merge(tx.get("related_work_order_id"),n,BigDecimal::add);}
            }
            for(var stock:rows(tables,"inventory"))if(material.equals(stock.get("material_id"))) {
                BigDecimal n=available(stock);if(n==null)known=false;else pool=pool.add(n);
            }
            pool=pool.subtract(finishedAllocated.getOrDefault(material,ZERO)).max(ZERO);
            BigDecimal need=ZERO;
            for(var work:grossByWork.entrySet()) {
                BigDecimal required=number(work.getValue().get(material));
                if(required!=null)need=need.add(required.subtract(issuedByWork.getOrDefault(work.getKey(),ZERO)).max(ZERO));
            }
            BigDecimal allocated=need.min(pool);
            var row=new LinkedHashMap<String,Object>();row.put("materialId",material);row.put("unit",units.get(material));
            row.put("calculationBasis","CURRENT_BOM_SCENARIO");row.put("readiness",known?(need.compareTo(pool)>0?"SCENARIO_SHORTAGE":"SCENARIO_COVERED"):"UNKNOWN");
            row.put("estimatedGrossRequirementQty",known?text(demand.getValue()):null);row.put("recordedIssuedQty",issuedKnown?text(issued):null);
            row.put("recordedIssueCoverage","OPEN_LINKED_WORK_ORDERS_ONLY");
            row.put("estimatedRequiredUnissuedQty",known?text(need):null);row.put("scenarioPublicAvailablePoolQty",known?text(pool):null);
            row.put("scenarioAllocatedQty",known?text(allocated):null);row.put("scenarioGapQty",known?text(need.subtract(allocated)):null);
            for(String field:List.of("grossRequirementQty","netIssuedQty","confirmedReturnedQty","requiredUnissuedQty","ownedReservedUsableQty","shortageNow","confirmedShortageNow"))row.put(field,null);
            row.put("assumptions",List.of("CURRENT_EFFECTIVE_BOM_NOT_FROZEN_WORK_ORDER_BOM","NO_UNRECORDED_RETURNS","ALL_OPEN_LINKED_WORK_ORDERS_REQUIRED","NORMAL_AVAILABLE_STOCK_NOT_A_QUALITY_RELEASE","SHARED_POOL_NOT_RESERVED"));
            result.add(row);
        }
        return result;
    }

    private static BigDecimal available(Map<String,String> s) {
        BigDecimal hand=number(s.get("quantity_on_hand")),reserved=number(s.get("quantity_reserved")),available=number(s.get("quantity_available"));
        if(hand==null||reserved==null||available==null||reserved.compareTo(hand)>0||available.compareTo(hand.subtract(reserved))!=0)return null;
        if("NORMAL".equals(s.get("inventory_status")))return available;
        if(Set.of("FROZEN","INSPECTING","ISOLATED").contains(s.getOrDefault("inventory_status","")))return ZERO;
        return null;
    }
    private static List<Map<String,String>> stockTransactions(Map<String,List<Map<String,String>>> tables) {
        Map<String,Map<String,String>> unique=new LinkedHashMap<>();var conflicts=new ArrayList<Map<String,String>>();
        for(String source:List.of("inventory_transaction","inventory_lineage"))for(var row:rows(tables,source)) {
            String id=row.get("transaction_id");
            if(id==null){conflicts.add(row);continue;}
            var prior=unique.putIfAbsent(id,row);
            if(prior!=null&&List.of("transaction_id","inventory_id","material_id","transaction_type","quantity","batch_no","related_work_order_id")
                    .stream().anyMatch(k -> !Objects.equals(prior.get(k),row.get(k))))conflicts.add(row);
        }
        var result=new ArrayList<>(unique.values());result.addAll(conflicts);return result;
    }
    private static boolean defectAffects(Map<String,String> defect,Map<String,String> inspection,String work,String material,String batch,Map<String,List<Map<String,String>>> tables) {
        if(inspection.get("inspection_id")!=null&&inspection.get("inspection_id").equals(defect.get("inspection_id")))return true;
        if(inspection.get("work_report_id")!=null&&inspection.get("work_report_id").equals(defect.get("work_report_id")))return true;
        boolean linked=work.equals(defect.get("work_order_id"))||defect.get("work_report_id")!=null&&rows(tables,"work_report").stream()
                .anyMatch(r -> defect.get("work_report_id").equals(r.get("report_id"))&&work.equals(r.get("work_order_id")));
        return linked&&(defect.get("material_id")==null||material.equals(defect.get("material_id")))
                &&(defect.get("batch_no")==null||batch.equals(defect.get("batch_no")));
    }
    private static List<Map<String,String>> rows(Map<String,List<Map<String,String>>> t,String name){return t.getOrDefault(name,List.of());}
    private static BigDecimal number(String n){return DeliveryQuantityCalculator.decimal(n);}
    private static String text(BigDecimal n){return DeliveryQuantityCalculator.text(n);}
}

package com.upc.computer.assistant.v2.runtime;

import com.upc.computer.assistant.v2.tools.business.InspectionSampleCheck;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** A fixed trace plan rooted exclusively at the authorized inspection number. */
final class InspectionTrace {
    private InspectionTrace() {}

    private static final Pattern LINKED_QUESTION=Pattern.compile("(?i)^(?:(?:请问|请|帮我|想知道|核对|查看|那|那么|再|查一下|看看)\\s*)*"
        +"(?:这张(?:检验)?单|该(?:检验)?单|此(?:检验)?单|它|(?:检验单\\s*)?QI[A-Z0-9_-]+)\\s*"
        +"(?:(?:所|分别|的|关联|相关|对应|涉及|了|到|哪些|什么|哪台|哪道|哪个|的)\\s*)*"
        +"(?:设备|生产记录|生产工序|工序|工单|报工|派工|报警|异常)"
        +"(?:(?:记录|和|与|、|设备|生产记录|工单|工序|报工|派工|报警|异常|能|可以|是否|确定|核实|吗|呢|了|是什么|是哪台|有哪些|有没有)\\s*)*[？?。！!]*$");
    private static final Pattern ADDITIONAL_SCOPE=Pattern.compile("全厂|全公司|全库|全局|所有(?:的)?设备|其他(?:批次|工单|订单|设备|检验单)|所有(?:的)?(?:工单|订单|检验单)");

    static boolean asksForLinkedRecords(String query){return LINKED_QUESTION.matcher(query.strip()).matches();}
    static boolean asksForAdditionalScope(String query){return ADDITIONAL_SCOPE.matcher(query).find();}

    static Map<String,Object> read(NaturalLanguageRuntime.Data data,String inspectionNo,
            BiFunction<String,Supplier<Map<String,Object>>,Map<String,Object>> call) {
        data.policy().require("QUALITY");
        if (inspectionNo==null || !inspectionNo.matches("(?i)QI[A-Z0-9_-]{1,60}"))
            throw new IllegalArgumentException("Invalid inspection identifier");
        var mapper=data.overview();
        var facts=new LinkedHashMap<String,Object>();
        facts.putAll(call.apply("INSPECTION_HEADER",()->{
            var header=mapper.traceInspection(inspectionNo);
            return Map.of("inspection",header==null||header.isEmpty()?List.of():List.of(header));
        }));
        var headers=rows(facts,"inspection");
        if(headers.isEmpty()){
            facts.put("status","NOT_FOUND");facts.put("summary","当前授权范围内没有匹配的检验单，请核对完整检验单号。");
            return facts;
        }
        var header=headers.get(0);
        long id=Long.parseLong(header.get("inspection_id").toString());
        facts.putAll(call.apply("INSPECTION_DETAILS",()->{
            var details=new LinkedHashMap<String,Object>();
            details.put("items",mapper.traceItems(id));
            details.put("defects",safeNotes(mapper.traceDefects(id)));
            details.put("inspection",headers);
            InspectionRevisionRead.append(data,id,details);
            return details;
        }));
        if(Set.of("QC","ADMIN").contains(data.policy().role()))
            facts.putAll(call.apply("INSPECTION_VISION",()->boundVision(header)));
        var missing=new ArrayList<String>();
        if(header.get("batch_no")!=null&&!header.get("batch_no").toString().isBlank()&&header.get("material_id")!=null) {
            facts.putAll(call.apply("INSPECTION_BATCH",()->{
                var result=new LinkedHashMap<String,Object>();
                result.put("sameBatchInspections",mapper.traceBatchInspections(id));
                result.put("comparison90d",mapper.traceBaseline(id));
                if(data.policy().allows("INVENTORY")) result.put("batchRecords",mapper.traceBatch(id));
                return result;
            }));
            if(data.policy().allows("INVENTORY")&&rows(facts,"batchRecords").isEmpty())missing.add("物料批次台账未匹配；同批检验仅按物料、批号和检验类别关联。");
            if(!rows(facts,"defects").isEmpty())
                facts.putAll(call.apply("INSPECTION_HISTORY",()->Map.of("similarDefects",safeNotes(mapper.traceSimilarDefects(id)))));
        } else missing.add("检验单缺少批号或物料，无法核对同批检验。");
        if(data.policy().allows("PRODUCTION") && data.policy().operator()==null && data.policy().customer()==null){
            facts.putAll(call.apply("INSPECTION_PRODUCTION",()->{
                var result=new LinkedHashMap<String,Object>();
                result.put("workOrder",mapper.traceWorkOrder(id));
                result.put("routeSteps",mapper.traceRoute(id));
                result.put("reportRows",visibleReports(mapper.traceReports(id),data.policy()));
                result.put("lotReports",mapper.traceLotReports(id));
                if(data.policy().allows("DISPATCH")) result.put("dispatchRows",visibleReports(mapper.traceDispatches(id),data.policy()));
                if(mapper.trainingArchiveAvailable()>0)
                    result.put("trainingHistory",mapper.traceTrainingHistory(id,data.policy().allows("DISPATCH")));
                return result;
            }));
            if(header.get("work_report_id")==null)missing.add("检验单未关联直接报工单。");
            if(rows(facts,"reportRows").isEmpty())missing.add("当前报工业务表没有关联记录。");
            if(facts.containsKey("dispatchRows")&&rows(facts,"dispatchRows").isEmpty())missing.add("当前派工业务表没有该工单记录。");
            if(data.policy().allows("EQUIPMENT")||data.policy().allows("ALARMS")){
                facts.putAll(call.apply("INSPECTION_EQUIPMENT",()->{
                    var result=new LinkedHashMap<String,Object>();
                    if(data.policy().allows("EQUIPMENT")) result.put("equipmentRows",mapper.traceEquipment(id));
                    if(data.policy().allows("ALARMS")) result.put("alarmRows",visibleReports(mapper.traceAlarms(id),data.policy()));
                    return result;
                }));
                if(facts.containsKey("equipmentRows")&&rows(facts,"equipmentRows").isEmpty())missing.add("报工及派工记录均未定位到具体设备。");
                else if(!rows(facts,"equipmentRows").isEmpty()&&rows(facts,"equipmentRows").stream().allMatch(r->"同工单背景设备".equals(r.get("link_basis"))))
                    missing.add("只有同工单背景设备，尚未找到本检验单或其不合格品直接关联的报工设备。");
            }
            if(data.policy().allows("ORDER")&&data.policy().allows("DISPATCH")&&!rows(facts,"workOrder").isEmpty()){
                long work=Long.parseLong(rows(facts,"workOrder").get(0).get("work_order_id").toString());
                facts.putAll(call.apply("INSPECTION_GRAPH",()->{
                    var graph=WorkTraceGraph.read(data,work);
                    var paths=rows(graph,"paths").stream().filter(p->String.valueOf(id).equals(String.valueOf(p.get("inspection_id")))).toList();
                    String status=String.valueOf(graph.get("status"));
                    if(status.equals("VERIFIED")&&paths.isEmpty()) status="NO_INSPECTION_PATH";
                    var result=new LinkedHashMap<String,Object>();
                    result.put("graphStatus",status);
                    result.put("graphPaths",paths);
                    if(graph.get("release")!=null)result.put("graphRelease",graph.get("release"));
                    return result;
                }));
            }
        } else missing.add("生产报工、工序和设备关联超出当前可查范围。");
        conclude(facts,header,missing);
        return facts;
    }

    private static Map<String,Object> boundVision(Map<String,Object> h){
        var archive=com.upc.computer.assistant.v2.vision.QualityEvidenceAnalysis.overview(List.of(h),true);
        @SuppressWarnings("unchecked") var vision=(Map<String,Object>)archive.get("visionEvidence");
        var all=rows(vision,"records");
        var records=all.stream().filter(r->matchesInspectionImage(h,r)).toList();
        var status=Objects.toString(vision.get("status"));
        if(records.isEmpty()&&!all.isEmpty()&&!"PARTIAL".equals(status))status="NO_USABLE_RECORD";
        return Map.of("visionStatus",status,"boundImages",records,"excludedImageRecords",all.size()-records.size(),
            "visionFingerprint",archive.get("visionEvidenceFingerprint"));
    }

    static boolean matchesInspectionImage(Map<String,Object> header,Map<String,Object> record){
        if(!Boolean.FALSE.equals(record.get("mock")) || header.get("inspection_id")==null ||
           !header.get("inspection_id").toString().equals(Objects.toString(record.get("inspectionId"),"")))return false;
        return Map.of("inspectionNo","inspection_no","workOrderId","work_order_id","batchNo","batch_no","materialId","material_id")
            .entrySet().stream().allMatch(k->record.get(k.getKey())==null ||
                record.get(k.getKey()).toString().equals(Objects.toString(header.get(k.getValue()),"")));
    }

    private static List<Map<String,Object>> visibleReports(List<Map<String,Object>> original,AssistantReadPolicy policy){
        return original.stream().map(row->{
            Map<String,Object> result=new LinkedHashMap<>(row);
            if(!policy.allows("EQUIPMENT"))result.remove("equipment_id");
            if(!policy.allows("DISPATCH")) result.remove("dispatch_id");
            return result;
        }).toList();
    }

    private static List<Map<String,Object>> safeNotes(List<Map<String,Object>> original){
        return original.stream().map(row->{
            Map<String,Object> result=new LinkedHashMap<>(row);
            for(String key:List.of("defect_description","remark"))if(result.get(key) instanceof String note)
                result.put(key,note.replaceAll("(?i)(?:bearer\\s+\\S+|(?:api[ _-]?key|password|secret|token|密码|密钥)\\s*[:=：]\\s*[^\\s,，;；]+)","[凭据已隐藏]")
                    .replaceAll("(?<![0-9])1[3-9][0-9]{9}(?![0-9])","[联系方式已隐藏]"));
            return result;
        }).toList();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String,Object>> rows(Map<String,Object> facts,String key) {
        return facts.get(key) instanceof List<?> list?(List<Map<String,Object>>)list:List.of();
    }

    private static BigDecimal decimal(Object value){
        return value==null?null:new BigDecimal(value.toString());
    }

    static String quantity(Object value) {
        var number=decimal(value);return number==null?"未知":number.stripTrailingZeros().toPlainString();
    }

    private static String rate(Object rejects,Object sample){
        var bad=decimal(rejects);var total=decimal(sample);
        if(bad==null||total==null||total.signum()<=0||bad.signum()<0||bad.compareTo(total)>0)return "";
        return quantity(bad.movePointRight(2).divide(total,2,java.math.RoundingMode.HALF_UP))+"%";
    }

    private static void personnel(Map<String,Object> facts,Map<String,Object> header,List<String> findings){
        if(header.get("inspector_name")!=null)findings.add("登记质检员："+header.get("inspector_name")+"。");
        else if(header.get("inspector_id")!=null)findings.add("质检员 ID "+header.get("inspector_id")+"，姓名未解析。");
        for(String group:List.of("reportRows","dispatchRows","trainingHistory")){
            var people=new LinkedHashSet<String>();
            for(var row:rows(facts,group)){
                if("检验工单与报工工单矛盾".equals(row.get("link_basis"))) continue;
                boolean report=group.equals("reportRows")||"REPORT".equals(row.get("record_kind"));
                Object reference=group.equals("trainingHistory")?row.get("record_no"):row.get(report?"report_no":"dispatch_no");
                people.add(Objects.toString(row.get("step_name"),"工序未登记")+" · "+Objects.toString(row.get("operator_name"),"姓名未登记")
                    +"（"+(report?"报工 ":"派工 ")+reference+"）");
            }
            if(!people.isEmpty())findings.add((group.equals("trainingHistory")?"实训历史补录（项目种子单据，仅同工单背景）":"工单生产记录")+"："+String.join("；",people)+"。");
        }
    }

    static List<String> quantityConflicts(Map<String,Object> header){
        return InspectionSampleCheck.conflicts(header,List.of(),List.of());
    }

    private static String state(Map<String,Object> h){
        return switch(Objects.toString(h.get("inspection_status"),"")){
            case "PENDING" -> "待检"; case "PASSED" -> "通过"; case "FAILED" -> "未通过";
            case "RECHECK_REQUIRED" -> "需复检"; default -> Objects.toString(h.get("inspection_status"),"未登记");
        };
    }

    private static String verdict(Map<String,Object> h){
        return switch(Objects.toString(h.get("inspection_result"),"")){
            case "PENDING" -> "待判定"; case "QUALIFIED" -> "合格"; case "UNQUALIFIED" -> "不合格";
            case "CONCESSION" -> "让步接收"; default -> Objects.toString(h.get("inspection_result"),"未登记");
        };
    }

    private static boolean changedCounts(Map<?,?> prior,Map<String,Object> current){
        for(String key:List.of("sample_quantity","qualified_quantity","unqualified_quantity")){
            var old=decimal(prior.get(key));var now=decimal(current.get(key));
            if(old!=null && now!=null && old.compareTo(now)!=0)return true;
        }
        return false;
    }

    private static boolean formalDecision(Map<String,Object> event){
        return Set.of("RECHECK_PASSED","RECHECK_FAILED").contains(Objects.toString(event.get("event_type"),""));
    }

    private static void revisionFindings(Map<String,Object> facts,Map<String,Object> h,List<String> findings,List<String> missing){
        var events=rows(facts,"inspectionEvents");
        var currentItems=rows(facts,"items");
        if(facts.get("legacyBaseline") instanceof Map<?,?> baseline && "DEMO_SEED_REFERENCE".equals(baseline.get("kind"))){
            findings.add("项目初始化脚本 "+baseline.get("source")+" 描述的初始样例为抽检 "+quantity(baseline.get("sample_quantity"))
                +" 件、合格 "+quantity(baseline.get("qualified_quantity"))+" 件、不合格 "+quantity(baseline.get("unqualified_quantity"))
                +" 件。此来源为演示数据定义，未提供现场签字原件。");
            if(baseline.get("items") instanceof List<?> list)for(Object obj:list){
                if(!(obj instanceof Map<?,?> item))continue;
                Object code=item.get("item_code");
                var current=currentItems.stream().filter(r->Objects.equals(code,r.get("item_code"))).findFirst();
                String saved=current.map(r->"；当前项目表保存值 "+Objects.toString(r.get("measured_value"),"未登记")+"，结果 "
                    +Objects.toString(r.get("result"),"未登记")+"，更新时间 "+Objects.toString(r.get("updated_at"),"未登记")).orElse("；当前未查到对应项目");
                findings.add("初始化脚本项目 "+code+" 的实测栏为 "+Objects.toString(item.get("measured_value"),"未登记")
                    +"，结果 "+Objects.toString(item.get("result"),"未登记")+saved+"。");
            }
        }
        if(h.get("created_at")!=null || h.get("updated_at")!=null)
            findings.add("当前检验表头创建于 "+Objects.toString(h.get("created_at"),"时间未登记")+"，最后保存于 "
                +Objects.toString(h.get("updated_at"),"时间未登记")+"。更新时间只能定位保存时点。");
        Map<String,String> labels=Map.ofEntries(
            Map.entry("OPENED","建单"),Map.entry("PRIOR_STATE","开始留痕时的既有记录"),Map.entry("INITIAL_ITEMS","初次项目快照"),
            Map.entry("ITEMS_SAVED","保存检验项目"),Map.entry("ITEMS_RESET","重置检验项目"),Map.entry("INITIAL_DECISION","初次判定"),
            Map.entry("DECISION_SAVED","保存判定"),Map.entry("RECHECK_REQUESTED","申请复检"),
            Map.entry("RECHECK_PASSED","正式复检通过"),Map.entry("RECHECK_FAILED","正式复检未通过"),
            Map.entry("SAMPLING_UPDATED","保存抽样件数"),Map.entry("HEADER_UPDATED","更新表头"),
            Map.entry("CLOSED","关闭检验单"),Map.entry("AUTO_CLOSED","自动关闭检验单"));
        for(var event:events){
            String type=Objects.toString(event.get("event_type"),"");
            String count=event.get("sample_quantity")==null?"":"，抽检 "+quantity(event.get("sample_quantity"))+"、合格 "
                    +quantity(event.get("qualified_quantity"))+"、不合格 "+quantity(event.get("unqualified_quantity"))+" 件";
            findings.add("留痕 "+Objects.toString(event.get("recorded_at"),"时间未登记")+"，"+labels.getOrDefault(type,type)+count
                    +"，流程状态："+state(event)+"，检验判定："+verdict(event)+"。");
        }
        if(events.stream().noneMatch(e->"INITIAL_DECISION".equals(e.get("event_type"))))
            missing.add("本次未取得初次判定的审计快照，无法从当前表头还原原始签核与修改人。");
        if(events.stream().noneMatch(InspectionTrace::formalDecision)){
            if("RECHECK".equals(h.get("inspection_type")))
                missing.add("本单类型为复检，表头登记判定为"+verdict(h)+"；未取得对应的正式复检结论事件或签核原件。");
            else missing.add("本单未取得正式复检结论事件。需复检状态、项目后续保存和同批其他单据均不足以认定本单已完成复检。");
        }
        var bindings=rows(facts,"sampleBindings");
        var measurements=rows(facts,"sampleMeasurements");
        if(bindings.isEmpty() && measurements.isEmpty()){
            missing.add("未取得本单缺陷对应的逐件样品标识和工序前后实测，无法定位异常首次出现的环节。");
        } else {
            for(var binding:bindings) {
                findings.add("缺陷单 "+Objects.toString(binding.get("nonconforming_no"),"未登记")+" 关联样品 "
                        +Objects.toString(binding.get("sample_label"),"标签未登记")+"，样本标识 "+Objects.toString(binding.get("unit_code"),"未登记")
                        +"，来源 "+Objects.toString(binding.get("source_ref"),"未登记")+"。");
                if(measurements.stream().noneMatch(m->Objects.equals(m.get("binding_id"),binding.get("binding_id"))))
                    missing.add("样品 "+Objects.toString(binding.get("sample_label"),"未登记")+" 尚缺工序前后实测。");
            }
            for(var defect:rows(facts,"defects"))
                if(bindings.stream().noneMatch(b->Objects.equals(b.get("nonconforming_no"),defect.get("nonconforming_no"))))
                    missing.add("缺陷单 "+defect.get("nonconforming_no")+" 未取得逐件样品关联。");
            measurementFindings(measurements,findings,missing);
        }
    }

    private static void measurementFindings(List<Map<String,Object>> measurements,List<String> findings,List<String> missing){
        var byId=new HashMap<String,Map<String,Object>>();
        for(var row:measurements)if(row.get("measurement_id")!=null)byId.put(row.get("measurement_id").toString(),row);
        var paired=new HashMap<Map<String,Object>,Map<String,Object>>();
        for(var next:measurements){
            if(next.get("prior_measurement_id")==null)continue;
            var prior=byId.get(next.get("prior_measurement_id").toString());
            if(prior==null || prior.get("binding_id")==null
                || List.of("binding_id","nonconforming_no","step_name","metric_name","unit").stream().anyMatch(k->!Objects.equals(prior.get(k),next.get(k))))continue;
            var before=prior.get("before_value")!=null?prior:next;
            var after=before==prior?next:prior;
            if(before.get("before_value")!=null && before.get("after_value")==null
                && after.get("before_value")==null && after.get("after_value")!=null)paired.put(before,after);
        }
        var afterRows=new HashSet<>(paired.values());
        for(var row:measurements){
            if(afterRows.contains(row))continue;
            var after=paired.getOrDefault(row,row);
            String sample=Objects.toString(row.get("sample_label"),"未登记样品");
            String step=Objects.toString(row.get("step_name"),"工序未登记")+" "+Objects.toString(row.get("metric_name"),"指标未登记");
            findings.add(sample+"，"+step+"，工序前 "+reading(row,"before")+"，工序后 "+reading(after,"after")+"。");
            if(row.get("before_value")==null || after.get("after_value")==null)
                missing.add(sample+" 的 "+step+" 缺少"+(row.get("before_value")==null?"前测":"后测")+"，无法比较前后变化。");
        }
    }

    private static String reading(Map<String,Object> row,String side){
        if(row.get(side+"_value")==null)return "缺失";
        return row.get(side+"_value")+" "+Objects.toString(row.get("unit"),"")+"（"
                +Objects.toString(row.get(side+"_measured_at"),"时间未登记")+"，来源 "+Objects.toString(row.get("source_ref"),"未登记")+"）";
    }

    static void conclude(Map<String,Object> facts,Map<String,Object> h,List<String> missing) {
        var findings=new ArrayList<String>();
        var items=rows(facts,"items");
        var defects=rows(facts,"defects");
        var conflicts=InspectionSampleCheck.conflicts(h,items,defects);
        var events=rows(facts,"inspectionEvents");
        boolean changedInitial=facts.get("legacyBaseline") instanceof Map<?,?> seed && changedCounts(seed,h)
            ||events.stream().anyMatch(e->"INITIAL_DECISION".equals(e.get("event_type")) && changedCounts(e,h));
        if(changedInitial && events.stream().noneMatch(e->formalDecision(e) && e.get("sample_quantity")!=null
                && e.get("qualified_quantity")!=null && e.get("unqualified_quantity")!=null && !changedCounts(e,h)
                && Objects.equals(e.get("inspection_result"),h.get("inspection_result"))))
            conflicts.add("初始参考与当前表头件数不同，未找到与当前件数及判定一致的正式复检结论。");
        var possible=new ArrayList<String>();
        var actions=new ArrayList<String>();
        String work=rows(facts,"workOrder").stream().map(r->Objects.toString(r.get("work_order_no"),"")).findFirst().orElse("");
        findings.add("检验单 "+h.get("inspection_no")+"（ID "+h.get("inspection_id")+"）"+(work.isEmpty()?"":"关联工单 "+work)+"，批号 "+Objects.toString(h.get("batch_no"),"未记录")+"。");
        String sampleLabel="PENDING".equals(h.get("inspection_status"))?"待检，登记抽样数量 ":"抽检 ";
        findings.add("当前表头"+sampleLabel+quantity(h.get("sample_quantity"))+" 件，登记合格 "+quantity(h.get("qualified_quantity"))+" 件、不合格 "+quantity(h.get("unqualified_quantity"))+" 件。流程状态："+state(h)+"；检验判定："+verdict(h)+"。");
        personnel(facts,h,findings);
        String sampleRate=rate(h.get("unqualified_quantity"),h.get("sample_quantity"));
        boolean pending="PENDING".equals(h.get("inspection_status"))||"PENDING".equals(h.get("inspection_result"));
        boolean reliable=!pending && conflicts.isEmpty() && !sampleRate.isBlank() && h.get("qualified_quantity")!=null;
        facts.put("sampleRateStatus",reliable?"RECORDED_SAMPLE":!conflicts.isEmpty()?"WITHHELD_CONFLICT":"UNAVAILABLE");
        facts.put("confirmedSampleRejectRate",reliable?sampleRate:null);
        facts.put("confirmedSamplePassRate",reliable?rate(h.get("qualified_quantity"),h.get("sample_quantity")):null);
        if(!conflicts.isEmpty()) conflicts.add("当前抽样不合格率待核实。缺陷登记、失败项目及不同阶段的快照均不能代作本轮不合格件数。");
        var bad=decimal(h.get("unqualified_quantity"));
        boolean reviewAcceptance="QUALIFIED".equals(h.get("inspection_result")) && bad!=null && bad.signum()>0;
        if(!conflicts.isEmpty())actions.add("请质检员先按本单检验轮次和样品标识核对表头、检验项目及缺陷登记，再计算本轮不合格率。");
        if(reviewAcceptance)actions.add("请质量负责人调取本批次适用的抽样方案、接受准则和签核或复判记录，将登记的 "
            +quantity(bad)+" 件不合格与方案逐项核对，确认放行依据及这批不合格件的处置去向。");
        if(pending)actions.add("请质检员核对本次抽样清单，完成项目实测和判定后再计算抽样结果。");
        revisionFindings(facts,h,findings,missing);
        if(reliable){
            findings.add("本单抽样不合格率 "+sampleRate+"，分母为本单抽检件数。");
            if(facts.get("comparison90d") instanceof Map<?,?> baseline && baseline.get("inspections")!=null){
                String referenceRate=rate(baseline.get("rejected"),baseline.get("sampled"));
                if(referenceRate.isBlank())missing.add("此前90天未查到同物料、同检验类别和类型的其他批次已完成有效样本，无法判断是否较历史升高。");
                else findings.add("此前90天同物料、同类别和类型的其他批次共有 "+baseline.get("inspections")+" 张有效检验单，不合格 "+quantity(baseline.get("rejected"))+"/"+quantity(baseline.get("sampled"))+"，按抽样件数加权为 "+referenceRate+"。");
            }
        }
        for(var other:rows(facts,"sameBatchInspections")){
            if(Objects.toString(h.get("inspection_id"),"").equals(Objects.toString(other.get("inspection_id"),""))||!"RECHECK".equals(other.get("inspection_type")))continue;
            String recheckRate=rate(other.get("unqualified_quantity"),other.get("sample_quantity"));
            if(!recheckRate.isBlank() && conflicts.isEmpty() && quantityConflicts(other).isEmpty()
                && "0".equals(Objects.toString(other.get("detail_count_conflict"),""))
                && Set.of("PASSED","FAILED").contains(Objects.toString(other.get("inspection_status"),"")))findings.add("同批复检单 "+other.get("inspection_no")+" 的表头登记 "+quantity(other.get("unqualified_quantity"))+"/"+quantity(other.get("sample_quantity"))+" 件不合格（"+recheckRate+"），需核对样品关联，复检样本单列。");
            else findings.add("同批另有复检类型单据 "+other.get("inspection_no")+"，流程状态："+state(other)+"，判定："+verdict(other)+"；其与本单样品的对应关系待核实。");
        }
        var failed=items.stream().filter(InspectionSampleCheck::failedItem)
            .map(r->r.get("item_name")+"（标准："+Objects.toString(r.get("standard_value"),"未登记")+"；实测："+Objects.toString(r.get("measured_value"),"未登记")+"）").toList();
        if(!items.isEmpty()){
            long passed=items.stream().filter(r->Set.of("PASSED","QUALIFIED","OK").contains(Objects.toString(r.get("result"),""))).count();
            findings.add("本单有 "+items.size()+" 条检验项目明细，已登记通过 "+passed+" 项、失败 "+failed.size()+" 项，其余 "
                +(items.size()-passed-failed.size())+" 项。项目数与受检样品件数分别统计。");
        }
        if(!failed.isEmpty()) findings.add("失败检验项："+String.join("、",failed)+"。");
        if(items.isEmpty())missing.add("没有检验项目明细。");
        if(!defects.isEmpty()) {
            findings.add("不合格品登记："+String.join("、",defects.stream().map(r->r.get("nonconforming_no")+" "+r.get("defect_type")).toList())+"。");
            if(defects.stream().allMatch(r->r.get("quantity")!=null)){
                var registered=defects.stream().map(r->decimal(r.get("quantity"))).reduce(BigDecimal.ZERO,BigDecimal::add);
                findings.add("登记数量合计 "+quantity(registered)+" 件。每件样品的归属需以逐件绑定记录核实。");
            }
            for(var nc:defects){
                findings.add(Objects.toString(nc.get("nonconforming_no"),"未编号缺陷单")+" 登记："+Objects.toString(nc.get("defect_description"),"未填写描述")
                    +"；数量 "+quantity(nc.get("quantity"))+" 件，处置 "+Objects.toString(nc.get("handle_method"),"未登记")+" / "+Objects.toString(nc.get("handle_status"),"未登记")+"。"
                    +("CONCESSION".equals(nc.get("handle_method"))?"让步接收仅为该条缺陷的处置，仍保留原缺陷登记。":""));
            }
        }
        var matchingDefects=defects.stream().filter(r->!different(r,h,"work_order_id")&&!different(r,h,"material_id")&&!different(r,h,"batch_no")).toList();
        if(matchingDefects.size()!=defects.size())
            conflicts.add("不合格品登记与检验单的工单、物料或批号不一致；仅保留按检验单外键关联的原记录。");
        if(rows(facts,"reportRows").stream().anyMatch(r->Objects.equals("检验工单与报工工单矛盾",r.get("link_basis")))) conflicts.add("直接报工记录的工单与检验单不一致，不能据此归因。");
        for(var report:rows(facts,"reportRows")){
            if("同工单背景".equals(report.get("link_basis")) || "检验工单与报工工单矛盾".equals(report.get("link_basis")))continue;
            if(report.get("step_name") instanceof String step){
                var reportedBad=decimal(report.get("unqualified_quantity"));
                String reference=report.get("link_basis")+" "+report.get("report_no")+" 登记工序“"+step+"”";
                if(reliable && reportedBad!=null && bad.compareTo(reportedBad)!=0)
                    possible.add(reference+"，该工序报工登记不合格 "+quantity(reportedBad)+" 件，本单抽样登记不合格 "+quantity(bad)
                        +" 件。请用报工交接清单与抽样序列号核对是否包含同一件，并比对交接和本次检验时的实测或照片。");
                else possible.add(reference+"，可优先复核其生产记录。");
            }
        }
        Set<String> routeNames=new LinkedHashSet<>();
        for(var step:rows(facts,"routeSteps")) if(step.get("step_name") instanceof String name) routeNames.add(name);
        for(var pair:List.of(List.of("接口","接口板装配"),List.of("主板","主板装配"),List.of("划伤","外观检验包装"))) {
            Set<String> sources=new LinkedHashSet<>();
            for(var item:failed)if(item.contains(pair.get(0)))sources.add("检验项“"+item+"”");
            for(var defect:matchingDefects)if(List.of("defect_type","defect_description","remark").stream().anyMatch(k->Objects.toString(defect.get(k),"").contains(pair.get(0))))
                sources.add(String.valueOf(defect.get("nonconforming_no")));
            if(!sources.isEmpty()&&routeNames.contains(pair.get(1)))possible.add(String.join("、",sources)+" 含“"+pair.get(0)+"”线索，可复核“"+pair.get(1)+"”。");
        }
        for(var row:matchingDefects){
            String description=Objects.toString(row.get("defect_description"),"");
            String ref=Objects.toString(row.get("nonconforming_no"),"");
            if(description.toUpperCase(Locale.ROOT).contains("HDMI")&&description.contains("无信号"))
                possible.add(ref+" 登记“"+description+"”。先用已知正常的信号源、线缆和同一测试模式交叉验证；若故障跟随样机，再核查接口板连接、排线及主板信号通路，记录换件前后复测结果。"
                    +(Objects.toString(row.get("remark"),"").contains("主板故障")?"处置备注写有“主板故障”，可据此调取拆解记录；该备注只涉及这一条。":""));
            if(description.contains("划伤")||description.contains("划痕"))
                possible.add(ref+" 登记“"+description+"”。比对来料、装壳前后和包装前留影，按首次出现的工序缩小范围；若多件同侧同位置，再查对应夹具的接触面、防护膜和周转隔垫。");
        }
        if(facts.containsKey("sameBatchInspections")){
            int sameBatch=rows(facts,"sameBatchInspections").size();
            findings.add("同物料、批号及检验类别下，共有 "+sameBatch+" 张检验单。"+(sameBatch>1?"同一批可能重复抽样。":""));
        }
        if(!matchingDefects.isEmpty() && rows(facts,"sampleBindings").isEmpty())
            actions.add("请质检员用 "+String.join("、",InspectionProductionReview.refs(matchingDefects,"nonconforming_no"))
                +" 对照逐件留样、检验轮次和处置记录，在现场照片上标出缺陷位置与形态，记录复测结果。");
        if(!possible.isEmpty())actions.addAll(new LinkedHashSet<>(possible));
        InspectionProductionReview.append(facts,findings,actions);
        var sources=sourceChecks(facts,h);
        if(actions.isEmpty())actions.add("按本单样品编号核对检验项目和签核记录，保存本次判定的依据。");
        if(!failed.isEmpty()||!matchingDefects.isEmpty()||(bad!=null&&bad.signum()>0))
            missing.add("本次可查记录中未取得经审批的根因或责任工序结论。");
        facts.put("status","FOUND");facts.put("findings",findings);facts.put("conflicts",conflicts);facts.put("possibleCauses",possible);facts.put("missingData",missing);
        var sample=decimal(h.get("sample_quantity"));
        var good=decimal(h.get("qualified_quantity"));
        String counts=sample!=null && good!=null && bad!=null ? "表头件数为 "+quantity(good)+" + "+quantity(bad)
            +(good.add(bad).compareTo(sample)==0?" = ":" ≠ ")+quantity(sample)+"（合格件数、登记不合格件数与抽检件数）。" : "表头件数未齐。";
        String conclusion=h.get("inspection_no")+" 登记为"+state(h)+"，检验判定为"+verdict(h)+"。"+counts;
        if(reliable)conclusion+="本单抽样不合格率为 "+sampleRate+"。";
        else if(!pending && "WITHHELD_CONFLICT".equals(facts.get("sampleRateStatus")))conclusion+="表头与关联记录存在差异，本轮抽样不合格率待核实。";
        if(reviewAcceptance)conclusion+="抽样中存在不合格件。批次能否按此结果放行，仍需用适用的接受准则和签核记录核实。";
        if(!failed.isEmpty() || !matchingDefects.isEmpty() || bad!=null && bad.signum()>0)conclusion+="现有记录不足以认定责任工序。";
        facts.put("diagnosticConclusion",conclusion);facts.put("verificationActions",List.copyOf(actions));facts.put("sourceChecks",sources);
        var parts=new ArrayList<String>();parts.add("## 结论\n\n"+conclusion);
        parts.add("## 单据与生产记录\n\n- "+String.join("\n- ",findings));
        if(!conflicts.isEmpty())parts.add("## 需要复核的差异\n\n- "+String.join("\n- ",conflicts));
        if(!sources.isEmpty())parts.add("## 来源核验\n\n- "+String.join("\n- ",sources));
        var ranked=new ArrayList<String>();
        for(int i=0;i<actions.size();i++)ranked.add((i+1)+". "+actions.get(i));
        parts.add("## 建议核查顺序\n\n"+String.join("\n",ranked));
        if(!missing.isEmpty())parts.add("## 证据缺口\n\n- "+String.join("\n- ",missing));
        facts.put("summary",String.join("\n\n",parts));
    }

    private static List<String> sourceChecks(Map<String,Object> facts,Map<String,Object> h){
        var sources=new ArrayList<String>();
        sources.add("MySQL 检验单 "+h.get("inspection_no")+" 的表头、项目及不合格品按检验单主键核对。");
        if(facts.containsKey("batchRecords")){
            var batch=rows(facts,"batchRecords");
            sources.add(batch.isEmpty()?"MySQL 批次台账未匹配到该物料与批号。":"MySQL 批次台账按物料和批号匹配到记录 ID "
                +String.join("、",InspectionProductionReview.refs(batch,"batch_id"))+"。");
        }
        var historical=rows(facts,"similarDefects");
        if(!historical.isEmpty())sources.add("MySQL 此前其他批次有同类型缺陷登记："+String.join("；",historical.stream()
            .map(r->r.get("inspection_no")+" / "+r.get("nonconforming_no")+"（"+r.get("defect_type")+"）").distinct().toList())+"。仅供对照症状和处置。");
        for(var row:rows(facts,"equipmentRows"))
            sources.add("MySQL "+row.get("link_basis")+"："+row.get("equipment_code")+" "+row.get("equipment_name")+"。");
        if(facts.containsKey("alarmRows")){
            var alarms=InspectionProductionReview.refs(rows(facts,"alarmRows"),"alarm_no");
            sources.add(alarms.isEmpty()?"MySQL 未查到检验时点及之前关联工单的报警。":"MySQL 检验时点及之前的同工单报警："+String.join("、",alarms)+"。报警与缺陷的对应关系待核实。");
        }
        if(facts.containsKey("graphStatus")){
            String detail=switch(Objects.toString(facts.get("graphStatus"),"")){
                case "NO_SNAPSHOT" -> "未取得业务图快照";
                case "NO_LIVE_PATH","NO_INSPECTION_PATH" -> "未核验到本检验单的完整路径";
                case "UNAVAILABLE" -> "业务图检索不可用";
                case "VERIFIED" -> "业务图快照与 MySQL 关联已核对";
                case "PARTIAL" -> "业务图快照仅部分通过核对";
                default -> "未取得可核验结果";
            };
            var paths=rows(facts,"graphPaths");
            sources.add("Neo4j "+detail+"，本单已核验路径 "+paths.size()+" 条。");
            for(var path:paths)sources.add("订单 ID "+path.get("order_id")+" → 订单行 "+path.get("order_item_id")
                +" → 计划 "+path.get("plan_id")+" → 工单 "+path.get("work_order_id")+" → 检验单 "+path.get("inspection_id")+"。此路径核对单据归属。");
        }
        return List.copyOf(sources);
    }

    private static boolean different(Map<String,Object> a,Map<String,Object> b,String key) {
        return a.get(key)!=null&&b.get(key)!=null&&!a.get(key).toString().equals(b.get(key).toString());
    }
}


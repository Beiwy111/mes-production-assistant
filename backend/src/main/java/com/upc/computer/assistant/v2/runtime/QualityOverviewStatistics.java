package com.upc.computer.assistant.v2.runtime;

import com.upc.computer.assistant.v2.tools.business.InspectionSampleCheck;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Statistics of authorized current inspection heads, with one denominator per comparable sampling group. */
final class QualityOverviewStatistics {
    private QualityOverviewStatistics() {}
    private enum Status { VALID, PENDING, CONFLICT, CONCESSION, ACCEPTANCE_REVIEW, INCOMPLETE }
    private enum Grouping { PRODUCT, BATCH, DAY }
    private static final String NOTE = "按上海时区的检验时间归期。每张检验单只取当前表头，保存记录和审核事件不作抽样轮次。复检按检验类型另列。各组样本合格率和不合格率以有效记录内的对应数量除以有效抽检数量，待检、让步、接受准则待核及矛盾记录均不计入。同一实物可能跨单重复抽样，结果仅描述已记录的检验样本。缺陷分布按关联一致的不合格品登记单数统计。";
    private static String value(Map<String,Object> row,String key){return Objects.toString(row.get(key),"").strip();}
    private static BigDecimal quantity(Map<String,Object> row,String key){
        try{return new BigDecimal(value(row,key));}catch(NumberFormatException invalid){return null;}
    }
    private static String decimal(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
    private record Sample(Map<String,Object> row,Status status,boolean duplicate,LocalDate day) {}
    static LocalDate day(Object time) {
        String text=Objects.toString(time,"").replace(' ','T');
        try{return OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate();}catch(DateTimeException ignored){}
        try{return LocalDateTime.parse(text).toLocalDate();}catch(DateTimeException ignored){}
        try{return LocalDate.parse(text);}catch(DateTimeException ignored){return null;}
    }
    private static Status classify(Map<String,Object> row,boolean duplicate,LocalDate day){
        String id=value(row,"record_id"),result=value(row,"status"),state=value(row,"inspection_status");
        if(duplicate || !InspectionSampleCheck.conflicts(row,List.of(),List.of()).isEmpty()
                || "CONTRADICTORY".equals(row.get("relation_status")))return Status.CONFLICT;
        if("CONCESSION".equals(result))return Status.CONCESSION;
        if("PENDING".equals(state)||"RECHECK_REQUIRED".equals(state)||"PENDING".equals(result))return Status.PENDING;
        var bad=quantity(row,"unqualified_quantity");
        if("QUALIFIED".equals(result)&&bad!=null&&bad.signum()>0)return Status.ACCEPTANCE_REVIEW;
        if(!("PASSED".equals(state)&&"QUALIFIED".equals(result)||"FAILED".equals(state)&&"UNQUALIFIED".equals(result)))return Status.INCOMPLETE;
        var total=quantity(row,"sample_quantity");var good=quantity(row,"qualified_quantity");
        if(id.isEmpty()||day==null||total==null||total.signum()<=0||good==null||bad==null
                ||value(row,"material_id").isEmpty()||value(row,"unit").isEmpty()
                ||value(row,"inspection_category").isEmpty()||value(row,"inspection_type").isEmpty())return Status.INCOMPLETE;
        return Status.VALID;
    }
    private static List<Sample> samples(List<Map<String,Object>> rows){
        var frequencies=new HashMap<String,Integer>();
        for(var row:rows)frequencies.merge(value(row,"record_id"),1,Integer::sum);
        var measured=new ArrayList<Sample>();
        for(var row:rows){
            String id=value(row,"record_id");boolean duplicate=!id.isEmpty()&&frequencies.get(id)>1;
            LocalDate date=day(row.get("event_at"));
            measured.add(new Sample(row,classify(row,duplicate,date),duplicate,date));
        }
        return measured;
    }
    private record Group(String material,String unit,String category,String type,String axis){
        static Group of(Sample sample,Grouping grouping){
            var row=sample.row;
            String axis=switch(grouping){case PRODUCT->"";case BATCH->value(row,"batch_no");case DAY->Objects.toString(sample.day,"未提供有效检验日期");};
            return new Group(value(row,"material_id"),value(row,"unit"),value(row,"inspection_category"),value(row,"inspection_type"),axis);
        }
        private static final Comparator<Group> ORDER=Comparator.comparing(Group::axis).thenComparing(Group::material)
                .thenComparing(Group::unit).thenComparing(Group::category).thenComparing(Group::type);
    }
    private static final class Totals {
        final String materialName;
        int records,valid,duplicates;
        BigDecimal total=BigDecimal.ZERO,good=BigDecimal.ZERO,bad=BigDecimal.ZERO;
        Totals(String name){materialName=name;}
        void add(Sample sample){
            records++;
            if(sample.duplicate)duplicates++;
            if(sample.status!=Status.VALID)return;
            valid++;
            total=total.add(quantity(sample.row,"sample_quantity"));
            good=good.add(quantity(sample.row,"qualified_quantity"));bad=bad.add(quantity(sample.row,"unqualified_quantity"));
        }
        String percent(BigDecimal quantity,boolean complete){
            return complete&&valid>0?quantity.movePointRight(2).divide(total,2,RoundingMode.HALF_UP).toPlainString():null;
        }
        Map<String,Object> fields(Group key,Grouping grouping,boolean complete){
            var map=new LinkedHashMap<String,Object>();
            map.put("material_id",key.material);map.put("material_name",materialName);map.put("unit",key.unit);
            map.put("inspection_category",key.category);map.put("inspection_type",key.type);
            if(grouping==Grouping.DAY){
                map.put("inspection_day",key.axis);
                map.put("sample_basis","按检验日期、物料、单位、检验类别和类型汇总有效表头数量，复检另列。");
            }
            if(grouping==Grouping.BATCH)map.put("batch_no",key.axis);
            map.put("inspection_record_count",records);map.put("valid_sample_record_count",valid);
            map.put("invalid_sample_record_count",records-valid);map.put("duplicate_record_count",duplicates);
            map.put("valid_sample_quantity",valid==0?null:decimal(total));
            map.put("valid_qualified_quantity",valid==0?null:decimal(good));map.put("valid_unqualified_quantity",valid==0?null:decimal(bad));
            boolean rateAvailable=complete&&(grouping==Grouping.PRODUCT||valid==records);
            map.put("sample_pass_percent",percent(good,rateAvailable));map.put("sample_fail_percent",percent(bad,rateAvailable));
            map.put("trend_status",!complete?"范围或判定筛选限制":valid==0?"无有效抽检分母":records==valid?"样本口径完整":
                    rateAvailable?"仅有效记录":"含待核记录，暂缺可比比例");
            return map;
        }
    }
    private static Map<Group,Totals> grouped(List<Sample> samples,Grouping grouping){
        Map<Group,Totals> groups=new TreeMap<>(Group.ORDER);
        for(var sample:samples){
            var key=Group.of(sample,grouping);
            if(grouping==Grouping.BATCH&&key.axis.isEmpty())continue;
            groups.computeIfAbsent(key,k->new Totals(value(sample.row,"material_name"))).add(sample);
        }
        return groups;
    }
    private static List<Map<String,Object>> groupRows(List<Sample> samples,Grouping grouping,boolean complete){
        return grouped(samples,grouping).entrySet().stream().map(e->e.getValue().fields(e.getKey(),grouping,complete)).toList();
    }
    static List<Map<String,Object>> trend(List<Map<String,Object>> rows,boolean complete){
        return groupRows(samples(rows),Grouping.DAY,complete);
    }
    static void alignLegacyMetrics(Map<String,Object> metrics,List<Map<String,Object>> rows,Map<String,Object> stats){
        boolean usable=Boolean.TRUE.equals(stats.get("complete"))&&"ALL_VERDICTS".equals(stats.get("rateScope"));
        metrics.put("validSampleRecordCount",usable?stats.get("validRecordCount"):null);
        metrics.put("conflictingSampleRecordCount",stats.get("conflictCount"));
        metrics.put("sampleRates",samples(rows).stream().map(s->{
            var entry=new LinkedHashMap<String,Object>();entry.put("inspectionId",s.row.get("record_id"));
            boolean valid=usable&&s.status==Status.VALID;
            entry.put("samplePassRate",valid?quantity(s.row,"qualified_quantity").divide(quantity(s.row,"sample_quantity"),6,RoundingMode.HALF_UP).toPlainString():null);
            entry.put("status",valid?"PER_INSPECTION_RECORDED_SAMPLE":"WITHHELD_"+(usable?s.status.name():"SCOPE"));
            return entry;
        }).toList());
        @SuppressWarnings("unchecked") var defects=(List<Map<String,Object>>)stats.get("defectDistribution");
        metrics.put("defectTypes",defects.stream().map(r->Map.of("defectType",r.get("defect_type"),"recordCount",r.get("record_count"))).toList());
        metrics.put("nonconformingRecordCount",defects.stream().mapToLong(r->((Number)r.get("record_count")).longValue()).sum());
        metrics.put("detailStatus","LINKED_DEFECT_REGISTRATIONS_COUNTED_ITEMS_NOT_AGGREGATED");
    }
    static Map<String,Object> calculate(List<Map<String,Object>> rows,boolean complete){
        var measured=samples(rows);var counts=new EnumMap<Status,Integer>(Status.class);
        for(var s:measured)counts.merge(s.status,1,Integer::sum);
        var stats=new LinkedHashMap<String,Object>();
        stats.put("inspectionCount",rows.size());stats.put("validRecordCount",counts.getOrDefault(Status.VALID,0));
        stats.put("pendingCount",counts.getOrDefault(Status.PENDING,0));stats.put("conflictCount",counts.getOrDefault(Status.CONFLICT,0));
        stats.put("concessionCount",counts.getOrDefault(Status.CONCESSION,0));stats.put("acceptanceReviewCount",counts.getOrDefault(Status.ACCEPTANCE_REVIEW,0));
        stats.put("incompleteCount",counts.getOrDefault(Status.INCOMPLETE,0));stats.put("complete",complete);
        stats.put("recheckRecordCount",(int)rows.stream().filter(r->"RECHECK".equals(r.get("inspection_type"))).count());
        var batchesByMaterial=new HashSet<List<String>>();int unidentified=0;
        for(var row:rows){
            String material=value(row,"material_id"),batch=value(row,"batch_no");
            if(material.isEmpty()||batch.isEmpty())unidentified++;else batchesByMaterial.add(List.of(material,batch));
        }
        stats.put("batchCount",batchesByMaterial.size());stats.put("unknownBatchRecordCount",unidentified);
        stats.put("sampleGroups",groupRows(measured,Grouping.PRODUCT,complete));
        var batches=grouped(measured,Grouping.BATCH);
        stats.put("batchGroups",batches.entrySet().stream().map(e->e.getValue().fields(e.getKey(),Grouping.BATCH,complete)).toList());
        stats.put("batchDifferences",complete?batchDifferences(batches):List.of());
        stats.put("scopeNote",NOTE);
        return stats;
    }

    private static List<Map<String,Object>> batchDifferences(Map<Group,Totals> batches){
        Map<Group,List<Map.Entry<Group,Totals>>> comparable=new TreeMap<>(Group.ORDER);
        for(var entry:batches.entrySet()){
            var key=entry.getKey();var sample=entry.getValue();
            if(sample.valid==0||sample.valid!=sample.records)continue;
            comparable.computeIfAbsent(new Group(key.material,key.unit,key.category,key.type,""),k->new ArrayList<>()).add(entry);
        }
        var differences=new ArrayList<Map<String,Object>>();
        for(var entries:comparable.values()){
            if(entries.size()<2)continue;
            entries.sort((a,b)->a.getValue().bad.multiply(b.getValue().total).compareTo(b.getValue().bad.multiply(a.getValue().total)));
            var low=entries.get(0);var high=entries.get(entries.size()-1);var key=high.getKey();
            var fact=new LinkedHashMap<String,Object>();
            fact.put("material_id",key.material);fact.put("material_name",high.getValue().materialName);fact.put("unit",key.unit);
            fact.put("inspection_category",key.category);fact.put("inspection_type",key.type);fact.put("comparable_batch_count",entries.size());
            fact.put("lowest",low.getValue().fields(low.getKey(),Grouping.BATCH,true));fact.put("highest",high.getValue().fields(key,Grouping.BATCH,true));
            fact.put("difference_percentage_points",difference(high.getValue(),low.getValue()));
            differences.add(fact);
        }
        return differences;
    }

    record Window(String basis,LocalDate start,LocalDate end) {}
    static List<Window> windows(LocalDate start,LocalDate end) {
        long days=ChronoUnit.DAYS.between(start,end);
        if(days<=0||days>366)return List.of();
        var windows=new ArrayList<Window>();
        boolean fullMonth=start.getDayOfMonth()==1&&end.equals(start.plusMonths(1));
        boolean monthToDate=start.getDayOfMonth()==1&&YearMonth.from(start).equals(YearMonth.from(end.minusDays(1)));
        if(monthToDate&&(fullMonth||days<=start.minusMonths(1).lengthOfMonth()))
            windows.add(new Window("MOM",start.minusMonths(1),fullMonth?start:start.minusMonths(1).plusDays(days)));
        else windows.add(new Window("PREVIOUS_PERIOD",start.minusDays(days),start));
        var priorStart=start.minusYears(1);var priorLast=end.minusDays(1).minusYears(1);
        var priorEnd=fullMonth?priorStart.plusMonths(1):priorLast.plusDays(1);
        if(!priorEnd.isAfter(start)&&(fullMonth||priorStart.plusYears(1).equals(start)&&priorLast.plusYears(1).equals(end.minusDays(1))
                &&ChronoUnit.DAYS.between(priorStart,priorEnd)==days))
            windows.add(new Window("YOY",priorStart,priorEnd));
        return windows;
    }

    static Map<String,Object> read(NaturalLanguageRuntime.Data data,List<Map<String,Object>> rows,boolean complete,
                                    BusinessToolRegistry.Request scope,boolean fullResultFilter,LocalDate today) {
        data.policy().require("QUALITY");
        var stats=calculate(rows,complete&&fullResultFilter);
        stats.put("complete",complete);
        stats.put("rateScope",fullResultFilter?"ALL_VERDICTS":"WITHHELD_VERDICT_FILTER");
        var ids=new LinkedHashSet<Long>();
        for(var row:rows){
            try{long id=Long.parseLong(value(row,"record_id"));if(id>0)ids.add(id);}
            catch(NumberFormatException ignored){}
        }
        var types=new TreeMap<String,Long>();long unlinked=0;
        var selected=List.copyOf(ids);
        for(int index=0;index<selected.size();index+=100){
            var batch=selected.subList(index,Math.min(index+100,selected.size()));
            for(var count:data.overview().qualityDefectCounts(batch,data.policy().customer(),data.policy().operator())){
                long n=((Number)count.get("record_count")).longValue();String type=value(count,"defect_type");
                if(type.isEmpty())unlinked+=n;else types.merge(type,n,Long::sum);
            }
        }
        stats.put("defectDistribution",types.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey())).map(e->Map.of("defect_type",e.getKey(),"record_count",e.getValue())).toList());
        stats.put("excludedDefectRegistrationCount",unlinked);
        stats.put("defectScope","按返回检验单的可信关联登记数计，一个样品可能有多条缺陷登记。");
        var comparisons=new ArrayList<Map<String,Object>>();
        if(scope!=null&&complete&&fullResultFilter){
            // An unbounded overview uses the current calendar month to date only for its comparison panel.
            LocalDate start=scope.startInclusive()==null?today.withDayOfMonth(1):day(scope.startInclusive());
            LocalDate end=scope.endExclusive()==null?today.plusDays(1):day(scope.endExclusive());
            var current=samples(rows).stream().filter(s->s.day!=null&&!s.day.isBefore(start)&&s.day.isBefore(end)).toList();
            if(!current.isEmpty())for(var window:windows(start,end)){
                var prior=data.overview().qualityPeriodRows(scope.orderId(),scope.keyword(),window.start+" 00:00:00",
                        window.end+" 00:00:00",scope.selectors(),data.policy().customer(),data.policy().operator());
                var comparison=new LinkedHashMap<String,Object>();
                comparison.put("basis",window.basis);comparison.put("previousStartInclusive",window.start.toString());
                comparison.put("previousEndExclusive",window.end.toString());comparison.put("currentStartInclusive",start.toString());
                comparison.put("currentEndExclusive",end.toString());
                boolean priorComplete=prior.size()<=2000;comparison.put("previousComplete",priorComplete);
                comparison.put("groups",priorComplete?compare(current,samples(prior)):List.of());
                comparisons.add(comparison);
            }
        }
        stats.put("periodComparisons",comparisons);
        return stats;
    }
    private static List<Map<String,Object>> compare(List<Sample> current,List<Sample> previous){
        var before=grouped(previous,Grouping.PRODUCT);
        var results=new ArrayList<Map<String,Object>>();
        for(var entry:grouped(current,Grouping.PRODUCT).entrySet()){
            var group=entry.getKey();var now=entry.getValue();var prior=before.getOrDefault(group,new Totals(now.materialName));
            var point=new LinkedHashMap<String,Object>();
            point.put("material_id",group.material);point.put("material_name",now.materialName);point.put("unit",group.unit);
            point.put("inspection_category",group.category);point.put("inspection_type",group.type);
            point.put("previous_sample_quantity",prior.valid>0?decimal(prior.total):null);
            point.put("current_sample_quantity",now.valid>0?decimal(now.total):null);
            point.put("previous_fail_percent",prior.percent(prior.bad,true));point.put("current_fail_percent",now.percent(now.bad,true));
            boolean comparable=prior.valid>0&&now.valid>0&&prior.records==prior.valid&&now.records==now.valid;
            // Subtract exact fractions before rounding the percentage-point difference.
            point.put("difference_percentage_points",comparable?difference(now,prior):null);
            point.put("comparison_status",comparable?"可比":prior.records==0?"上期缺少同口径记录":"样本缺失或存在待核记录");
            results.add(point);
        }
        return results;
    }
    private static String difference(Totals a,Totals b){
        return a.bad.multiply(b.total).subtract(b.bad.multiply(a.total)).movePointRight(2)
                .divide(a.total.multiply(b.total),2,RoundingMode.HALF_UP).toPlainString();
    }
}

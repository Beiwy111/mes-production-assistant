package com.upc.computer.assistant.v2.runtime;

import java.math.*;
import java.util.*;
import com.upc.computer.assistant.v2.tools.business.InspectionSampleCheck;

/** Compares disjoint, permission-scoped inspection samples for the same batch; never estimates whole-lot yield. */
final class BatchQualityComparison {
    static boolean requested(String query){
        return (query.contains("不良率")||query.contains("不合格率"))
            &&java.util.regex.Pattern.compile("升高|升降|提高|上升|增加|下降|降低|减少|比较|对比|相比|变化|两期|前后").matcher(query).find();
    }
    private record Group(String material,String name,String unit,String category,String type) {
        static Group of(Map<String,Object> row){
            return new Group(value(row,"material_id"),value(row,"material_name"),value(row,"unit"),
                value(row,"inspection_category"),value(row,"inspection_type"));
        }
    }
    private static String value(Map<String,Object> row,String field){return Objects.toString(row.get(field),"");}
    private static BigDecimal count(Map<String,Object> row,String field){
        try{var n=new BigDecimal(value(row,field));return n.signum()<0?null:n;}catch(NumberFormatException invalid){return null;}
    }
    private static final class Sample {
        BigDecimal total=BigDecimal.ZERO,bad=BigDecimal.ZERO;boolean invalid;
        void add(Map<String,Object> row,boolean unique){
            var n=count(row,"sample_quantity");var g=count(row,"qualified_quantity");var b=count(row,"unqualified_quantity");
            var result=value(row,"status");var state=value(row,"inspection_status");
            if(!unique||n==null||g==null||b==null||g.add(b).compareTo(n)!=0||!InspectionSampleCheck.conflicts(row,List.of(),List.of()).isEmpty()||
                !((state.equals("PASSED")&&result.equals("QUALIFIED"))||(state.equals("FAILED")&&result.equals("UNQUALIFIED")))){
                invalid=true;return;
            }
            total=total.add(n);bad=bad.add(b);
        }
        BigDecimal rate(){return invalid||total.signum()==0?null:bad.multiply(BigDecimal.valueOf(100)).divide(total,2,RoundingMode.HALF_UP);}
        String description(){return rate()==null?"无可比样本或记录待核对":bad.stripTrailingZeros().toPlainString()+"/"+total.stripTrailingZeros().toPlainString()+"（"+rate().toPlainString()+"%）";}
    }
    static Map<String,Object> read(NaturalLanguageRuntime.Data db,String batch,List<BusinessDateRange> periods){
        db.policy().require("QUALITY");
        if(periods.size()!=2||batch==null||!BusinessSelectors.BATCH_CODE.matcher(batch).matches())
            throw new IllegalArgumentException("An exact batch and two comparison periods are required");
        var results=new ArrayList<BusinessToolRegistry.Result>();
        for(var period:periods)results.add(BusinessToolRegistry.execute(db.overview(),
            new BusinessToolRegistry.Request("QUALITY",null,null,period.fromTimestamp(),period.toTimestamp(),Map.of("batch_no",batch)),db.policy()));
        var allRecords=new ArrayList<Map<String,Object>>();
        var idCounts=new HashMap<String,Integer>();
        for(var result:results)for(var row:result.rows()){
            Map<String,Object> record=new LinkedHashMap<>();
            row.forEach((k,v)->{if(!k.equals("search_text"))record.put(k,v==null?null:v.toString());});
            allRecords.add(record);
            idCounts.merge(value(row,"record_id"),1,Integer::sum);
        }
        var groups=new LinkedHashMap<Group,Sample[]>();
        var labels=periods.stream().map(p->p.start()+" 至 "+p.endExclusive().minusDays(1)).toList();
        for(int i=0;i<2;i++)for(var row:results.get(i).rows()){
            var group=Group.of(row);var samples=groups.computeIfAbsent(group,k->new Sample[]{new Sample(),new Sample()});
            String id=value(row,"record_id");
            samples[i].add(row,!id.isBlank()&&idCounts.get(id)==1&&!group.material.isBlank()&&!group.unit.isBlank()
                &&!group.category.isBlank()&&!group.type.isBlank()&&batch.equalsIgnoreCase(value(row,"batch_no")));
        }
        boolean complete=results.stream().allMatch(r->r.total()==r.rows().size());
        var summary=new ArrayList<String>();var comparisons=new ArrayList<Map<String,Object>>();
        summary.add("批号 "+batch+"，依检验时间比较前期 "+labels.get(0)+"（"+results.get(0).total()+" 单）与后期 "+labels.get(1)+"（"+results.get(1).total()+" 单）。");
        for(var entry:groups.entrySet()){
            var group=entry.getKey();var samples=entry.getValue();
            if(!complete){samples[0].invalid=true;samples[1].invalid=true;}
            var first=samples[0].rate();var second=samples[1].rate();
            var diff=first==null||second==null?null:second.subtract(first);
            var item=new LinkedHashMap<String,Object>();
            item.put("material_id",group.material);item.put("unit",group.unit);item.put("inspection_category",group.category);
            item.put("inspection_type",group.type);item.put("before",samples[0].description());item.put("after",samples[1].description());
            item.put("difference_percentage_points",diff==null?null:diff.toPlainString());comparisons.add(item);
            summary.add(group.name+"（"+group.category+" / "+group.type+"）抽检不合格数/抽检总数，前期 "+samples[0].description()+"，后期 "+samples[1].description()+"。"
                +(diff==null?"不能判定升降。":diff.signum()==0?"显示精度内持平。":(diff.signum()>0?"增加 ":"减少 ")+diff.abs()+" 个百分点。"));
        }
        if(groups.isEmpty())summary.add("两个期间均无该批授权检验样本，无法判断不良率变化。");
        summary.add("同物料、单位及检验类别内按抽检件数加权，复检另列。以上为现存检验记录；不能据此判定整批良率或设备根因。");
        return Map.of("traceConclusion",String.join("\n",summary),"batchComparison",comparisons,"comparisonPeriods",labels,
            "overview",Map.of("domain","QUALITY","rows",allRecords,"totalRecords",results.get(0).total()+results.get(1).total(),
                "returnedRecords",allRecords.size(),"truncated",!complete,"period","COMPARISON","dateField","inspected_at","timeZone","Asia/Shanghai","selectors",Map.of("batch_no",batch)));
    }
}

package com.upc.computer.assistant.v2.tools.business;

import java.math.BigDecimal;
import java.util.*;

/** Cross-check one header against its own inspection items and NC records before reporting rates. */
public final class InspectionSampleCheck {
    private InspectionSampleCheck() {}

    private static BigDecimal number(Object value) {
        try {return value == null ? null : new BigDecimal(value.toString());}
        catch (NumberFormatException invalid) {return null;}
    }

    public static boolean failedItem(Map<String,?> item) {
        return Set.of("FAILED","UNQUALIFIED","NG").contains(Objects.toString(item.get("result"),""));
    }

    public static List<String> conflicts(Map<String,?> header, List<? extends Map<String,?>> items,
                                         List<? extends Map<String,?>> defects) {
        var findings = new ArrayList<String>();
        if ("1".equals(Objects.toString(header.get("detail_count_conflict"),"")))
            findings.add("表头件数或判定与关联检验项、不合格品登记存在差异，需核对样品和轮次。");
        var sample = number(header.get("sample_quantity"));
        var good = number(header.get("qualified_quantity"));
        var bad = number(header.get("unqualified_quantity"));
        String state = Objects.toString(header.get("inspection_status"),"");
        String verdict = Objects.toString(header.get("inspection_result"),Objects.toString(header.get("status"),""));
        if (Arrays.asList(sample,good,bad).stream().anyMatch(n -> n != null && n.signum() < 0))
            findings.add("存在负数件数，需要核对原始记录。");
        if (sample != null && good != null && bad != null && good.add(bad).compareTo(sample) != 0
                && (!"PENDING".equals(state) || good.add(bad).compareTo(sample) > 0))
            findings.add("合格数与不合格数之和不等于抽检数，需要核对计数口径。");
        if (("UNQUALIFIED".equals(verdict) || "FAILED".equals(state)) && bad != null && bad.signum() == 0)
            findings.add("登记未通过或判定不合格，但表头不合格件数为零。");
        if (("FAILED".equals(state) && "QUALIFIED".equals(verdict)) || ("PASSED".equals(state) && "UNQUALIFIED".equals(verdict)))
            findings.add("检验流程状态与判定结果相互矛盾。");
        if (items.stream().anyMatch(InspectionSampleCheck::failedItem)
                && ((bad != null && bad.signum() == 0) || "QUALIFIED".equals(verdict)))
            findings.add("当前项目仍有失败项，需与表头件数、判定及对应样品复核。");
        if (!defects.isEmpty() && bad != null) {
            var quantities = defects.stream().map(r -> number(r.get("quantity"))).toList();
            if (quantities.stream().allMatch(Objects::nonNull)) {
                var registered = quantities.stream().reduce(BigDecimal.ZERO,BigDecimal::add);
                if (bad.compareTo(registered) != 0)
                    findings.add("表头不合格 " + bad.stripTrailingZeros().toPlainString() + " 件，缺陷登记合计 "
                            + registered.stripTrailingZeros().toPlainString() + " 件。需按样品标识核对登记是否重叠、是否属同轮抽样。");
            } else if (bad.signum() == 0)
                findings.add("表头不合格为零，存在关联不合格品记录，登记数量不完整。");
        }
        return findings;
    }
}

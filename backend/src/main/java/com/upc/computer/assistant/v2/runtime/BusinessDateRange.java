package com.upc.computer.assistant.v2.runtime;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.regex.*;

/** Literal dates come from the user's words, never from model-invented SQL or dates. */
public record BusinessDateRange(LocalDate start, LocalDate endExclusive, String remainingText) {
    private static final Pattern ISO=Pattern.compile("(?<!\\d)(\\d{4}-\\d{2}-\\d{2})(?:\\s*(?:至|到|~|～)\\s*(\\d{4}-\\d{2}-\\d{2}))?(?!\\d)");
    private static final Pattern RELATIVE=Pattern.compile("昨天|前天|上周|上个月|上月|(?:最近|近|过去)\\s*(\\d+|七)\\s*天");
    /** Two user-specified calendar ranges for a comparison. Never infer a missing comparison period. */
    static java.util.List<BusinessDateRange> comparison(String text, LocalDate today){
        var dates=ISO.matcher(text);var periods=new java.util.ArrayList<BusinessDateRange>();
        while(dates.find())periods.add(parse(dates.group(),today));
        if(periods.size()<2)return java.util.List.of();
        if(periods.size()!=2||RELATIVE.matcher(text).find())throw new IllegalArgumentException("Need exactly two date ranges");
        periods.sort(java.util.Comparator.comparing(BusinessDateRange::start));
        if(periods.get(0).endExclusive().isAfter(periods.get(1).start()))throw new IllegalArgumentException("Overlapping periods");
        var rest=ISO.matcher(text).replaceAll(" ");
        if(Pattern.compile("昨天|今天|本月|上月|本周|上周|截至|截止|\\d{4}年|\\d+月\\d+日").matcher(rest).find())
            throw new IllegalArgumentException("Additional time constraint");
        return periods.stream().map(p->new BusinessDateRange(p.start(),p.endExclusive(),rest)).toList();
    }
    public static BusinessDateRange parse(String query,LocalDate today) {
        Matcher date=ISO.matcher(query);LocalDate from=null,to=null;String remaining=query;
        if(date.find()) {
            from=LocalDate.parse(date.group(1));to=LocalDate.parse(date.group(2)==null?date.group(1):date.group(2)).plusDays(1);
            remaining=query.substring(0,date.start())+query.substring(date.end());
            if(date.find()||RELATIVE.matcher(remaining).find())throw new IllegalArgumentException("AMBIGUOUS_DATE_RANGE");
        } else {
            Matcher relative=RELATIVE.matcher(query);
            if(!relative.find())return null;
            String phrase=relative.group();
            if(relative.group(1)!=null) {
                int days="七".equals(relative.group(1))?7:Integer.parseInt(relative.group(1));if(days<1||days>366)throw new IllegalArgumentException("INVALID_DATE_RANGE");
                from=today.minusDays(days-1);to=today.plusDays(1);
            } else switch(phrase) {
                case "昨天" -> {from=today.minusDays(1);to=today;}
                case "前天" -> {from=today.minusDays(2);to=today.minusDays(1);}
                case "上周" -> {to=today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));from=to.minusWeeks(1);}
                default -> {to=today.withDayOfMonth(1);from=to.minusMonths(1);}
            }
            remaining=query.substring(0,relative.start())+query.substring(relative.end());
            if(relative.find())throw new IllegalArgumentException("AMBIGUOUS_DATE_RANGE");
        }
        if(!to.isAfter(from)||from.getYear()<2000||to.getYear()>2101)throw new IllegalArgumentException("INVALID_DATE_RANGE");
        // Do not silently accept a second, unsupported temporal constraint.
        if(Pattern.compile("今天|本月|本周|截至|截止|\\d{4}年|\\d+月\\d+日").matcher(remaining).find())throw new IllegalArgumentException("AMBIGUOUS_DATE_RANGE");
        return new BusinessDateRange(from,to,remaining);
    }
    public String fromTimestamp(){return start+" 00:00:00";}
    public String toTimestamp(){return endExclusive+" 00:00:00";}
}

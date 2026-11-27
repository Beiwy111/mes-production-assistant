package com.upc.computer.service;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Request-scoped observation of real computations; absent outside assistant requests. */
public final class ProductionCalculationProgress {
    private ProductionCalculationProgress() {}
    private static final ThreadLocal<Consumer<Map<String,Object>>> SINK = new ThreadLocal<>();
    public static <T> T observe(Consumer<Map<String,Object>> sink, Supplier<T> work) {
        var previous=SINK.get(); SINK.set(sink);
        try { return work.get(); } finally { if(previous==null)SINK.remove();else SINK.set(previous); }
    }
    public static <T> T stage(String key,String label,Supplier<T> work,java.util.function.Function<T,String> summary) {
        event(key,label,"RUNNING","正在读取并核算");
        try { T result=work.get();event(key,label,"COMPLETED",summary.apply(result));return result; }
        catch(RuntimeException error){event(key,label,"FAILED","本阶段未完成；请查看业务错误说明");throw error;}
    }
    private static void event(String key,String label,String status,String detail) {
        var sink=SINK.get(); if(sink!=null)sink.accept(Map.of("key",key,"label",label,"status",status,"detail",detail));
    }
}

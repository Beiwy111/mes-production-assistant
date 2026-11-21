package com.upc.computer.assistant.v2.orders;

import com.upc.computer.common.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/assistant-v2/orders")
public class OrderDraftController {
    private final OrderSessionGuard sessions;private final OrderDraftService service;
    public OrderDraftController(OrderSessionGuard sessions,OrderDraftService service){this.sessions=sessions;this.service=service;}
    @ModelAttribute public void noCache(jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    @ExceptionHandler(Exception.class) public Result<?> failure(Exception error){
        if(error instanceof BusinessException business)return Result.fail(business.getCode(),business.getMessage());
        return Result.fail(503,"订单服务暂不可用，请刷新草稿状态后再操作；系统不会自动重复提交");
    }
    @GetMapping("/status") public Result<?> status(@RequestHeader(value="Authorization",required=false) String auth){return Result.success(service.status(sessions.require(auth)));}
    @PostMapping("/turn") public Result<?> turn(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody Map<String,Object> body){
        fields(body,Set.of("sessionId","requestId","text"));return Result.success(service.turn(sessions.require(auth),auth,text(body,"sessionId"),text(body,"requestId"),text(body,"text")));
    }
    @GetMapping("/drafts/{id}") public Result<?> get(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id){return Result.success(service.get(sessions.require(auth),id));}
    @PostMapping("/drafts/{id}/revise") public Result<?> revise(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id,@RequestBody Map<String,Object> body){
        fields(body,Set.of("version","requestId","fields"));if(!(body.get("fields") instanceof Map<?,?> patch))throw new BusinessException(400,"缺少修改字段");
        Map<String,Object> values=new LinkedHashMap<>();patch.forEach((k,v)->values.put(String.valueOf(k),v));return Result.success(service.revise(sessions.require(auth),id,version(body),text(body,"requestId"),values));
    }
    @PostMapping("/drafts/{id}/confirm") public Result<?> confirm(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id,@RequestBody Map<String,Object> body){fields(body,Set.of("version","requestId"));return Result.success(service.confirm(sessions.require(auth),id,version(body),text(body,"requestId")));}
    @PostMapping("/drafts/{id}/cancel") public Result<?> cancel(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id,@RequestBody Map<String,Object> body){fields(body,Set.of("version","requestId"));return Result.success(service.cancel(sessions.require(auth),id,version(body),text(body,"requestId")));}
    private void fields(Map<String,Object> body,Set<String> allowed){if(body==null||!allowed.equals(body.keySet()))throw new BusinessException(400,"请求字段不完整或含未允许字段");}
    private String text(Map<String,Object> body,String key){if(!(body.get(key) instanceof String s))throw new BusinessException(400,"请求字段格式错误");return s;}
    private int version(Map<String,Object> body){if(!(body.get("version") instanceof Integer n)||n<0)throw new BusinessException(400,"草稿版本无效");return n;}
}

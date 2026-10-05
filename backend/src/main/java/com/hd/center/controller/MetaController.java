package com.hd.center.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 枚举值与中文标签，供前端下拉与展示使用。
 */
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    @GetMapping("/enums")
    public Map<String, List<Map<String, String>>> enums() {
        Map<String, List<Map<String, String>>> map = new LinkedHashMap<>();
        map.put("Role", list(new String[][]{
                {"ADMIN", "护士长/管理"}, {"NURSE", "护士"}, {"DOCTOR", "医生"},
                {"ENGINEER", "工程"}, {"FINANCE", "收费"}}));
        map.put("InfectionMarker", list(new String[][]{
                {"NONE", "无"}, {"HBV", "乙肝"}, {"HCV", "丙肝"}, {"TP", "梅毒"}, {"OTHER", "其他传染"}}));
        map.put("RiskLevel", list(new String[][]{
                {"LOW", "低危"}, {"MEDIUM", "中危"}, {"HIGH", "高危"}}));
        map.put("AccessType", list(new String[][]{
                {"AVF", "自体动静脉内瘘"}, {"AVG", "移植物内瘘"}, {"CATHETER", "带 cuff 导管"}}));
        map.put("TransportMode", list(new String[][]{
                {"SELF", "自行来院"}, {"FAMILY", "家属接送"}, {"SHUTTLE", "班车接送"}}));
        map.put("Shift", list(new String[][]{
                {"AM", "上午班"}, {"PM", "下午班"}, {"EVE", "晚班"}}));
        map.put("MachineZone", list(new String[][]{
                {"NORMAL", "普通区"}, {"HBV", "乙肝区"}, {"HCV", "丙肝区"}, {"ISOLATION", "隔离区"}}));
        map.put("MachineStatus", list(new String[][]{
                {"IDLE", "空闲"}, {"IN_USE", "使用中"}, {"DISINFECTING", "消毒中"},
                {"FAULT", "故障"}, {"MAINTENANCE", "维护中"}}));
        map.put("SessionStatus", list(new String[][]{
                {"SCHEDULED", "已排班"}, {"ASSESSED", "已评估"}, {"ON_DIALYSIS", "透析中"},
                {"OFF_DIALYSIS", "已下机"}, {"FINISHED", "已完成"}, {"ABSENT", "请假/缺席"}, {"CANCELLED", "已取消"}}));
        map.put("AccessCondition", list(new String[][]{
                {"NORMAL", "正常"}, {"REDNESS", "红肿"}, {"SWELLING", "肿胀"},
                {"BRUIT_WEAK", "震颤减弱"}, {"INFECTION", "感染迹象"}}));
        map.put("DepartureMode", list(new String[][]{
                {"SELF", "自行离开"}, {"FAMILY", "家属接回"}, {"SHUTTLE", "班车送回"},
                {"WHEELCHAIR", "轮椅"}, {"STRETCHER", "平车"}}));
        map.put("DisinfectionType", list(new String[][]{
                {"DAILY", "日常消毒"}, {"HEAT", "热消毒"}, {"CITRIC_ACID", "柠檬酸消毒"},
                {"FILTER_CHANGE", "滤芯更换"}, {"WATER_TREATMENT", "水处理维护"}, {"WATER_TEST", "水质检测"}}));
        map.put("DisinfectionResult", list(new String[][]{
                {"PASS", "合格"}, {"FAIL", "不合格"}}));
        map.put("TxnType", list(new String[][]{
                {"PRE_DRAW", "预领"}, {"USE", "使用"}, {"RETURN", "归还"},
                {"WASTE", "损耗"}, {"RESTOCK", "入库"}}));
        map.put("ExceptionType", list(new String[][]{
                {"LATE", "患者迟到"}, {"LEAVE", "临时请假"}, {"INFECTION_CHANGE", "感染标记变化"},
                {"MACHINE_FAULT", "机器故障"}, {"CONSUMABLE_SHORTAGE", "耗材不足"},
                {"HYPOTENSION", "透析中低血压"}, {"ORDER_CHANGE", "医嘱调整"}, {"OTHER", "其他"}}));
        map.put("ExceptionStatus", list(new String[][]{
                {"OPEN", "待处理"}, {"PROCESSING", "处理中"}, {"RESOLVED", "已解决"}}));
        map.put("BillingStatus", list(new String[][]{
                {"PENDING", "待核对"}, {"CONFIRMED", "已核对"}}));
        map.put("RescheduleAction", list(new String[][]{
                {"DELAY", "延后"}, {"SWITCH_MACHINE", "换机"},
                {"SHIFT_CHANGE", "改班次"}, {"SWAP", "协商调换"}}));
        map.put("NotifyChannel", list(new String[][]{
                {"PHONE", "电话通知"}, {"SMS", "短信通知"},
                {"IN_PERSON", "现场告知"}, {"FAMILY_PHONE", "通知家属"}}));
        return map;
    }

    private List<Map<String, String>> list(String[][] pairs) {
        return java.util.Arrays.stream(pairs)
                .map(p -> Map.of("value", p[0], "label", p[1]))
                .toList();
    }
}

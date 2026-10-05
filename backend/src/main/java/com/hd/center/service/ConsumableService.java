package com.hd.center.service;

import com.hd.center.entity.*;
import com.hd.center.entity.Enums.ExceptionType;
import com.hd.center.entity.Enums.TxnType;
import com.hd.center.repository.ConsumableRepository;
import com.hd.center.repository.ConsumableTxnRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 耗材库存与流水。出库（预领/使用/损耗）为负数量，入库（归还/补货）为正数量。
 */
@Service
@RequiredArgsConstructor
public class ConsumableService {

    private final ConsumableRepository consumableRepo;
    private final ConsumableTxnRepository txnRepo;
    private final ExceptionService exceptionService;

    /** 单次透析标准预领套件：耗材编码 -> 数量 */
    public static final Map<String, Integer> STANDARD_KIT = Map.of(
            "DIALYZER", 1,
            "BLOODLINE", 1,
            "NEEDLE", 2,
            "HEPARIN", 1,
            "SALINE", 1,
            "DIALYSATE", 1);

    /**
     * 记录一条耗材流水并更新库存。
     *
     * @param qty 正数，方向由 type 决定（PRE_DRAW/USE/WASTE 出库，RETURN/RESTOCK 入库）
     */
    @Transactional
    public ConsumableTxn transact(Consumable consumable, TxnType type, int qty,
                                  Long sessionId, String patientName, String operator, String note) {
        boolean outbound = type == TxnType.PRE_DRAW || type == TxnType.USE || type == TxnType.WASTE;
        int signed = outbound ? -Math.abs(qty) : Math.abs(qty);
        int newStock = consumable.getStock() + signed;

        ConsumableTxn txn = new ConsumableTxn();
        txn.setConsumableId(consumable.getId());
        txn.setConsumableName(consumable.getName());
        txn.setQty(signed);
        txn.setType(type);
        txn.setSessionId(sessionId);
        txn.setPatientName(patientName);
        txn.setOperatorName(operator);
        txn.setNote(note);

        if (newStock < 0) {
            // 库存不足：仍然记录并产生异常，便于工程/库管补货
            exceptionService.raise(ExceptionType.CONSUMABLE_SHORTAGE, null, null, null,
                    "耗材「" + consumable.getName() + "」库存不足：当前 " + consumable.getStock()
                            + "，本次" + typeLabel(type) + "需要 " + Math.abs(qty), operator);
        }
        consumable.setStock(newStock);
        consumableRepo.save(consumable);
        if (newStock <= consumable.getSafeStock()) {
            exceptionService.raise(ExceptionType.CONSUMABLE_SHORTAGE, null, null, null,
                    "耗材「" + consumable.getName() + "」库存 " + newStock + " 已低于安全库存 "
                            + consumable.getSafeStock() + "，请及时补货", operator);
        }
        return txnRepo.save(txn);
    }

    /** 为一条治疗单预领标准套件 */
    @Transactional
    public List<ConsumableTxn> preDrawForSession(DialysisSession session, String operator) {
        return preDrawForSession(session, operator, "治疗单#" + session.getId() + " 标准套件预领");
    }

    /** 为一条治疗单预领标准套件（自定义流水备注，如故障改排后重新预领） */
    @Transactional
    public List<ConsumableTxn> preDrawForSession(DialysisSession session, String operator, String note) {
        List<ConsumableTxn> result = new ArrayList<>();
        List<Consumable> all = consumableRepo.findAllByOrderByCode();
        for (Map.Entry<String, Integer> entry : STANDARD_KIT.entrySet()) {
            Consumable c = all.stream().filter(x -> x.getCode().equals(entry.getKey())).findFirst().orElse(null);
            if (c == null) continue;
            result.add(transact(c, TxnType.PRE_DRAW, entry.getValue(), session.getId(),
                    session.getPatient().getName(), operator, note));
        }
        return result;
    }

    public static String typeLabel(TxnType type) {
        return switch (type) {
            case PRE_DRAW -> "预领";
            case USE -> "使用";
            case RETURN -> "归还";
            case WASTE -> "损耗";
            case RESTOCK -> "入库";
        };
    }
}

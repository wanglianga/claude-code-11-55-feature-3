package com.group.carbon.controller;

import com.group.carbon.common.AuthContext;
import com.group.carbon.common.Types.Role;
import com.group.carbon.dto.Requests.ConversionRuleReq;
import com.group.carbon.dto.Requests.DecideAdjudicationReq;
import com.group.carbon.dto.Requests.ReceiveEvidenceReq;
import com.group.carbon.dto.Requests.ReasonReq;
import com.group.carbon.service.EvidenceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 采购凭证数量裁定接口：
 * 凭证接收（哈希/版本/重发）、换算规则、裁定查询与人工决策、运行证据快照。
 */
@RestController
@RequestMapping("/api/evidence")
public class EvidenceController {
  private final EvidenceService evidenceService;

  public EvidenceController(EvidenceService evidenceService) {
    this.evidenceService = evidenceService;
  }

  // ---------------- 凭证接收 ----------------

  @PostMapping("/documents")
  public Object receive(@Valid @RequestBody ReceiveEvidenceReq req) {
    AuthContext.requireRole(Role.ANALYST, Role.ADMIN);
    return evidenceService.receive(AuthContext.require().username(), req);
  }

  @GetMapping("/documents")
  public Object listDocuments(@RequestParam(required = false) String period,
                              @RequestParam(required = false) String voucherNo) {
    return evidenceService.listDocs(period, voucherNo);
  }

  @GetMapping("/documents/{id}")
  public Map<String, Object> document(@PathVariable Long id) {
    return evidenceService.docDetail(id);
  }

  @PostMapping("/documents/{id}/void")
  public Object voidDocument(@PathVariable Long id, @RequestBody(required = false) ReasonReq req) {
    AuthContext.requireRole(Role.AUDITOR, Role.ADMIN);
    return evidenceService.voidDocument(id, req != null ? req.reason() : null);
  }

  // ---------------- 换算规则 ----------------

  @GetMapping("/conversions")
  public Object conversions() {
    return evidenceService.listConversions();
  }

  @PostMapping("/conversions")
  public Object saveConversion(@Valid @RequestBody ConversionRuleReq req) {
    AuthContext.requireRole(Role.ANALYST, Role.ADMIN);
    return evidenceService.saveRule(AuthContext.require().username(), req);
  }

  @PostMapping("/conversions/{id}/suspend")
  public Object suspendConversion(@PathVariable Long id) {
    AuthContext.requireRole(Role.AUDITOR, Role.ADMIN);
    return evidenceService.suspendRule(id);
  }

  // ---------------- 数量裁定 ----------------

  @GetMapping("/adjudications")
  public Object adjudications(@RequestParam(required = false) String period,
                              @RequestParam(required = false) String status) {
    return evidenceService.listAdjudications(period, status);
  }

  @GetMapping("/adjudications/{id}")
  public Map<String, Object> adjudication(@PathVariable Long id) {
    return evidenceService.adjudicationDetail(id);
  }

  /** 人工裁定：采用量必须在可行组合集合内，理由必填。 */
  @PostMapping("/adjudications/{id}/decide")
  public Object decide(@PathVariable Long id, @Valid @RequestBody DecideAdjudicationReq req) {
    AuthContext.requireRole(Role.AUDITOR, Role.ADMIN);
    return evidenceService.decide(id, AuthContext.require().username(), req);
  }

  /** 凭证补齐后手动触发重裁（一般由凭证接收自动触发）。 */
  @PostMapping("/adjudications/re-run")
  public Object rerun(@RequestParam String period, @RequestParam Long plantId,
                      @RequestParam String voucherNo) {
    AuthContext.requireRole(Role.AUDITOR, Role.ADMIN);
    return evidenceService.adjudicate(period, plantId, voucherNo);
  }

  /** 运行冻结的裁定证据快照。 */
  @GetMapping("/runs/{runId}/bindings")
  public Object runBindings(@PathVariable Long runId) {
    return evidenceService.bindingsOfRun(runId);
  }

  /** 期间待裁定清单（仪表盘/前端角标用）。 */
  @GetMapping("/pending")
  public Object pending(@RequestParam String period) {
    return evidenceService.pendingOfPeriod(period);
  }
}

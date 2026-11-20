#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
凭证数量裁定专项验收回放（S6~S9）：
  S6 三证冲突：发票/过磅单数量互斥 → 多可行组合待裁定 → 人工裁定记录采用/排除理由；
              禁止用金额/平均数填活动量；待裁定期间核算与披露阻断
  S7 部分交货与退货守恒：分批交货累计、退货冲减、无对应收货的退货被守恒约束排除
  S8 单位无法换算：缺密度/包装规格维持待补；补录默认密度/重发带规格文件后可裁定
  S9 重发声明：重发文件新建版本（旧版本 SUPERSEDED、哈希变化）、旧裁定失效、
              运行冻结快照不受影响、重裁前新运行待证并阻断披露
前置：后端已启动（默认 http://127.0.0.1:8080），数据库为初始种子状态（与 verify.py 相同，
先执行 reset-db.sh 并重启后端）；本脚本为一次性回放，重复执行前需再次重置数据库。
使用 2025-07/2025-08 期间，不依赖 verify.py 的回放结果。
"""
import hashlib
import json
import sys
import time
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:8080'
API = BASE + '/api'
PASSED, FAILED = [], []


def req(method, path, body=None, token=None):
    url = API + path
    data = json.dumps(body).encode('utf-8') if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header('Content-Type', 'application/json')
    if token:
        r.add_header('X-Auth-Token', token)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            raw = resp.read().decode('utf-8')
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8')
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {'error': raw}


def check(name, cond, detail=''):
    if cond:
        PASSED.append(name)
        print(f'  [PASS] {name}')
    else:
        FAILED.append((name, detail))
        print(f'  [FAIL] {name} :: {detail}')


def login(u, p):
    s, b = req('POST', '/auth/login', {'username': u, 'password': p})
    if s != 200:
        print(f'登录失败 {u}: {b}')
        sys.exit(1)
    return b['token']


def wait_run(token, run_id, timeout=40):
    for _ in range(timeout * 2):
        s, b = req('GET', f'/runs/{run_id}', token=token)
        if s == 200 and b['run']['status'] in ('COMPLETED', 'FAILED'):
            return b
        time.sleep(0.5)
    raise RuntimeError(f'运行 {run_id} 超时')


def sha(s):
    return hashlib.sha256(s.encode()).hexdigest()


def doc(token, doc_no, doc_type, plant_id, period, voucher, order_no, delivery_no,
        qty, unit='t', tier=1, signed=True, material='PURCH_STEEL',
        density=None, pack_spec=None, return_no=None, hash_tag='v1'):
    fields = [
        {'name': 'QUANTITY', 'text': f'{qty}{unit}', 'numValue': qty, 'unit': unit,
         'extractStatus': 'OK'},
        {'name': 'UNIT', 'text': unit, 'unit': unit, 'extractStatus': 'OK'}]
    if density is not None:
        fields.append({'name': 'DENSITY', 'numValue': density, 'unit': 't/m3',
                       'extractStatus': 'OK'})
    if pack_spec is not None:
        fields.append({'name': 'PACK_SPEC', 'numValue': pack_spec, 'unit': 'kg/PCS',
                       'extractStatus': 'OK'})
    body = {
        'docNo': doc_no, 'docType': doc_type, 'supplier': '专项验收供应商',
        'plantId': plant_id, 'period': period, 'voucherNo': voucher,
        'orderNo': order_no, 'deliveryNo': delivery_no, 'returnNo': return_no,
        'materialCode': material, 'signed': signed, 'authorityTier': tier,
        'docDate': period + '-12', 'fileName': doc_no + '.pdf',
        'fileHash': sha(doc_no + '|' + hash_tag), 'fileSize': 20480, 'fields': fields}
    s, b = req('POST', '/evidence/documents', body, token=token)
    assert s == 200, f'凭证登记失败 {doc_no}: {s} {b}'
    return b


def rerun(token, plant_id, period, voucher):
    s, b = req('POST',
               f'/evidence/adjudications/re-run?period={period}&plantId={plant_id}&voucherNo={voucher}',
               token=token)
    assert s == 200, f'重裁失败 {voucher}: {s} {b}'
    return b


def adj_detail(token, adj_id):
    s, b = req('GET', f'/evidence/adjudications/{adj_id}', token=token)
    assert s == 200, b
    return b


def find_adj(token, period, voucher, status=None):
    s, lst = req('GET', f'/evidence/adjudications?period={period}', token=token)
    out = [a for a in lst if a['voucherNo'] == voucher and (status is None or a['status'] == status)]
    return out[0] if out else None


def proc_batch(token, plant_id, period, rows):
    body = {'plantId': plant_id, 'category': 'PROCUREMENT', 'period': period,
            'rows': rows}
    s, b = req('POST', '/batches', body, token=token)
    assert s == 200, f'采购批次上传失败: {s} {b}'
    return b


def main():
    print(f'== 目标: {BASE}')
    admin = login('admin', 'admin123')
    analyst = login('analyst', 'analyst123')
    auditor = login('auditor', 'auditor123')
    finance = login('finance', 'finance123')

    # 初始状态守卫：2025-07 不应已有裁定（防止在脏库上重复回放）
    s, existing = req('GET', '/evidence/adjudications?period=2025-07', token=admin)
    if existing:
        print('数据库非初始种子状态（2025-07 已存在裁定），请先执行 reset-db.sh 并重启后端再回放。')
        sys.exit(2)

    # ========== S6 三证冲突 ==========
    print('\n[S6] 三证冲突：互斥候选、人工裁定、禁止估值填数、披露阻断')
    # 权限：分析员不能人工裁定，审核员不能登记凭证
    s, _ = req('POST', '/evidence/adjudications/1/decide',
               {'adoptedQty': 1, 'reason': 'x'}, token=analyst)
    check('S6 分析员人工裁定被拒(403)', s == 403, f's={s}')
    doc(analyst, 'PO-V-2001', 'PURCHASE_ORDER', 1, '2025-07', 'V-2001',
        'PO-2025-07-01', None, 100, tier=3, signed=False)
    s, _ = req('POST', '/evidence/documents',
               {'docNo': 'X', 'docType': 'INVOICE', 'supplier': 's', 'plantId': 1,
                'period': '2025-07', 'docDate': '2025-07-12', 'fileName': 'x.pdf',
                'fileHash': sha('x'), 'fields': [
                   {'name': 'QUANTITY', 'numValue': 1, 'unit': 't'},
                   {'name': 'UNIT', 'unit': 't'}]}, token=auditor)
    check('S6 审核员登记凭证被拒(403)', s == 403, f's={s}')
    # 发票 100t vs 过磅单 96t，同一交货号 → 互斥候选
    doc(analyst, 'INV-V-2001', 'INVOICE', 1, '2025-07', 'V-2001',
        'PO-2025-07-01', 'DN-201', 100)
    doc(analyst, 'WN-V-2001', 'WEIGHNOTE', 1, '2025-07', 'V-2001',
        'PO-2025-07-01', 'DN-201', 96)
    adj = rerun(auditor, 1, '2025-07', 'V-2001')
    check('S6 互斥数量下维持待裁定(PENDING)', adj['status'] == 'PENDING', str(adj['status']))
    check('S6 枚举到 2 个可行组合(96/100)', adj['candidateCount'] == 2, str(adj['candidateCount']))
    detail = adj_detail(auditor, adj['id'])
    totals = sorted(float(c['netQty']) for c in detail['feasibleCandidates'])
    check('S6 可行集合为 {96,100}，无金额/平均数', totals == [96.0, 100.0], str(totals))
    # 待裁定期间：核算待证、披露阻断
    b1 = proc_batch(analyst, 1, '2025-07', [
        {'plantId': 1, 'productLineId': 1, 'energyType': 'PURCH_STEEL',
         'quantity': 100, 'voucherNo': 'V-2001'}])
    req('POST', f"/batches/{b1['id']}/verify", token=auditor)
    s, run_pending = req('POST', '/runs', {'period': '2025-07'}, token=analyst)
    rd_pending = wait_run(admin, run_pending['id'])
    pe = [r for r in rd_pending['results'] if r['energyType'] == 'PURCH_STEEL' and r['plantId'] == 1]
    check('S6 未裁定采购指标状态 PENDING_EVIDENCE 且活动量为空',
          pe and pe[0]['status'] == 'PENDING_EVIDENCE' and pe[0]['activityQty'] is None,
          str(pe[:1]))
    s, rep7 = req('POST', '/reports',
                  {'title': '2025年7月披露报告', 'period': '2025-07', 'runId': run_pending['id']},
                  token=analyst)
    check('S6 待证报告保持待核(RECONCILE_FAILED)',
          rep7['status'] == 'RECONCILE_FAILED', str(rep7['status']))
    s, _ = req('POST', f"/reports/{rep7['id']}/approve", token=finance)
    check('S6 待证报告审批被拒(409)', s == 409, f's={s}')
    s, _ = req('POST', f"/reports/{rep7['id']}/publish", token=finance)
    check('S6 待证报告发布被拒(409)', s == 409, f's={s}')
    # 禁止采用可行集合外的数量
    s, b = req('POST', f"/evidence/adjudications/{adj['id']}/decide",
               {'adoptedQty': 99, 'reason': '尝试用平均数'}, token=auditor)
    check('S6 可行集合外数量(99)被拒(409)', s == 409, f's={s} {b}')
    # 人工裁定 96t（过磅单实测）
    s, decided = req('POST', f"/evidence/adjudications/{adj['id']}/decide",
                     {'adoptedQty': 96, 'reason': '过磅单为现场计量且与入库实收一致，采用 96t，发票 100t 为开票口径予以排除'},
                     token=auditor)
    check('S6 人工裁定 96t 生效', s == 200 and decided['status'] == 'DECIDED'
          and float(decided['adoptedQty']) == 96.0, str(b))
    detail2 = adj_detail(auditor, decided['id'])
    inv_ev = next(e for e in detail2['evidence'] if e['docNo'] == 'INV-V-2001')
    wn_ev = next(e for e in detail2['evidence'] if e['docNo'] == 'WN-V-2001')
    check('S6 发票标记排除且记录互斥组与落选理由',
          inv_ev['role'] == 'EXCLUDED' and inv_ev['exclusiveGroup'] is not None
          and '互斥候选' in inv_ev['excludeReason'], json.dumps(inv_ev, ensure_ascii=False)[:220])
    check('S6 过磅单标记采用', wn_ev['role'] == 'ADOPTED', str(wn_ev['role']))
    check('S6 证据条目冻结文件哈希', all(len(e['docHash']) == 64 for e in detail2['evidence']))

    # ========== S7 部分交货与退货守恒 ==========
    print('\n[S7] 部分交货：订单守恒、分批累计、退货冲减、无主退货被排除')
    doc(analyst, 'PO-V-2002', 'PURCHASE_ORDER', 1, '2025-07', 'V-2002',
        'PO-2025-07-02', None, 100, tier=3, signed=False)
    doc(analyst, 'INV-V-2002', 'INVOICE', 1, '2025-07', 'V-2002',
        'PO-2025-07-02', 'DN-202', 60)
    doc(analyst, 'WN-V-2002', 'WEIGHNOTE', 1, '2025-07', 'V-2002',
        'PO-2025-07-02', 'DN-202', 60)
    adj7a = rerun(auditor, 1, '2025-07', 'V-2002')
    check('S7 首批交货 60t 唯一解自动裁定（订单 100 守恒内）',
          adj7a['status'] == 'DECIDED' and float(adj7a['adoptedQty']) == 60.0, str(adj7a))
    # 第二批交货 40t → 累计 100t
    doc(analyst, 'INV-V-2002B', 'INVOICE', 1, '2025-07', 'V-2002',
        'PO-2025-07-02', 'DN-203', 40)
    doc(analyst, 'WN-V-2002B', 'WEIGHNOTE', 1, '2025-07', 'V-2002',
        'PO-2025-07-02', 'DN-203', 40)
    adj7b = rerun(auditor, 1, '2025-07', 'V-2002')
    check('S7 两批交货累计 100t 自动重裁，旧裁定失效',
          adj7b['status'] == 'DECIDED' and float(adj7b['adoptedQty']) == 100.0
          and adj7b['supersedesAdjId'] is not None, str(adj7b))
    old7 = adj_detail(auditor, adj7a['id'])['adjudication']
    check('S7 旧裁定保留且状态 INVALID', old7['status'] == 'INVALID', str(old7['status']))
    # 退货 10t（指向第二批交货）→ 可行组合 {100, 90}
    doc(analyst, 'RT-V-2002', 'RETURN_NOTE', 1, '2025-07', 'V-2002',
        'PO-2025-07-02', 'DN-203', 10, tier=3, signed=False)
    # 无对应收货的退货（不同订单、未知交货号）→ 守恒约束永远排除
    doc(analyst, 'RT-ORPHAN', 'RETURN_NOTE', 1, '2025-07', 'V-2002',
        'PO-UNKNOWN', 'DN-404', 5, tier=3, signed=False)
    adj7c = rerun(auditor, 1, '2025-07', 'V-2002')
    check('S7 退货后出现 2 个可行组合（100 不含退货 / 90 冲减）',
          adj7c['status'] == 'PENDING' and adj7c['candidateCount'] == 2, str(adj7c))
    s, decided7 = req('POST', f"/evidence/adjudications/{adj7c['id']}/decide",
                      {'adoptedQty': 90, 'reason': '退货 10t 已验收入库冲减，净收货 90t'},
                      token=auditor)
    check('S7 人工裁定净量 90t', s == 200 and float(decided7['adoptedQty']) == 90.0, str(b))
    d7 = adj_detail(auditor, decided7['id'])
    rt_in = next(e for e in d7['evidence'] if e['docNo'] == 'RT-V-2002')
    rt_out = next(e for e in d7['evidence'] if e['docNo'] == 'RT-ORPHAN')
    check('S7 挂钩交货的退货纳入采用组合', rt_in['role'] == 'ADOPTED', str(rt_in['role']))
    check('S7 无主退货被守恒约束排除并记录理由',
          rt_out['role'] == 'EXCLUDED' and '守恒' in rt_out['excludeReason'],
          json.dumps(rt_out, ensure_ascii=False)[:200])
    # 补录采购批次行 V-2002
    b2 = proc_batch(analyst, 1, '2025-07', [
        {'plantId': 1, 'productLineId': 1, 'energyType': 'PURCH_STEEL',
         'quantity': 100, 'voucherNo': 'V-2002'}])
    req('POST', f"/batches/{b2['id']}/verify", token=auditor)

    # ========== S8 单位无法换算（密度/包装规格缺失 → 待补） ==========
    print('\n[S8] 单位无法换算：缺密度/包装规格维持待补，补齐后可裁定')
    doc(analyst, 'INV-V-2003', 'INVOICE', 2, '2025-07', 'V-2003',
        'PO-2025-07-03', 'DN-203', 30, unit='m3')
    adj8 = rerun(auditor, 2, '2025-07', 'V-2003')
    d8 = adj_detail(auditor, adj8['id'])
    unc = next(e for e in d8['evidence'] if e['docNo'] == 'INV-V-2003')
    check('S8 缺密度 m3 凭证无法换算', unc['conversionStatus'] == 'UNCONVERTIBLE')
    check('S8 裁定维持待裁定（待补），理由含密度缺失',
          adj8['status'] == 'PENDING' and '密度' in adj8['reason'], str(adj8['reason']))
    # 补录规则库默认密度 7.85 t/m3 → 30×7.85=235.5t
    s, _ = req('POST', '/evidence/conversions',
               {'energyType': 'PURCH_STEEL', 'fromUnit': 'm3', 'toUnit': 't',
                'requiresDensity': True, 'defaultDensity': 7.85}, token=analyst)
    check('S8 补录密度换算规则', s == 200, f's={s}')
    adj8b = rerun(auditor, 2, '2025-07', 'V-2003')
    check('S8 补密度后自动裁定 235.5t',
          adj8b['status'] == 'DECIDED' and abs(float(adj8b['adoptedQty']) - 235.5) < 0.001,
          str(adj8b))
    # 计件单位缺包装规格 → 待补；重发带规格文件（新版本）→ 可裁定
    doc(analyst, 'WN-V-2004', 'WEIGHNOTE', 2, '2025-07', 'V-2004',
        'PO-2025-07-04', 'DN-204', 200, unit='PCS')
    adj8c = rerun(auditor, 2, '2025-07', 'V-2004')
    check('S8 缺包装规格 PCS 凭证维持待补', adj8c['status'] == 'PENDING', str(adj8c['status']))
    v2 = doc(analyst, 'WN-V-2004', 'WEIGHNOTE', 2, '2025-07', 'V-2004',
             'PO-2025-07-04', 'DN-204', 200, unit='PCS', pack_spec=25, hash_tag='v2')
    check('S8 重发文件自动新建版本 v2', v2['versionNo'] == 2 and v2['supersedesId'] is not None,
          str(v2['versionNo']))
    adj8d = rerun(auditor, 2, '2025-07', 'V-2004')
    check('S8 带包装规格重发后裁定 5t（200件×25kg÷1000）',
          adj8d['status'] == 'DECIDED' and abs(float(adj8d['adoptedQty']) - 5.0) < 0.001,
          str(adj8d))
    s, docs8 = req('GET', '/evidence/documents?voucherNo=V-2004', token=admin)
    statuses = {d['versionNo']: d['status'] for d in docs8}
    hashes = {d['versionNo']: d['fileHash'] for d in docs8}
    check('S8 旧版本 SUPERSEDED/新版本 RECEIVED 且哈希不同',
          statuses.get(1) == 'SUPERSEDED' and statuses.get(2) == 'RECEIVED'
          and hashes.get(1) != hashes.get(2), str(statuses))
    # 成都工厂采购批次（V-2003/V-2004）
    b3 = proc_batch(analyst, 2, '2025-07', [
        {'plantId': 2, 'productLineId': 1, 'energyType': 'PURCH_STEEL',
         'quantity': 30, 'voucherNo': 'V-2003'},
        {'plantId': 2, 'productLineId': 1, 'energyType': 'PURCH_STEEL',
         'quantity': 200, 'voucherNo': 'V-2004'}])
    req('POST', f"/batches/{b3['id']}/verify", token=auditor)

    # ========== S9 重发声明：版本/哈希/失效/运行快照不变/披露阻断 ==========
    print('\n[S9] 重发环境声明：新版本+哈希变化，旧裁定失效，运行快照冻结不变')
    doc(analyst, 'PO-V-3001', 'PURCHASE_ORDER', 2, '2025-08', 'V-3001',
        'PO-2025-08-01', None, 50, tier=3, signed=False)
    doc(analyst, 'INV-V-3001', 'INVOICE', 2, '2025-08', 'V-3001',
        'PO-2025-08-01', 'DN-301', 50)
    ed1 = doc(analyst, 'ED-V-3001', 'ENV_DECLARATION', 2, '2025-08', 'V-3001',
              'PO-2025-08-01', 'DN-301', 50, tier=2, hash_tag='decl-v1')
    adj9 = rerun(auditor, 2, '2025-08', 'V-3001')
    check('S9 初版三证一致自动裁定 50t',
          adj9['status'] == 'DECIDED' and float(adj9['adoptedQty']) == 50.0, str(adj9))
    b4 = proc_batch(analyst, 2, '2025-08', [
        {'plantId': 2, 'productLineId': 1, 'energyType': 'PURCH_STEEL',
         'quantity': 50, 'voucherNo': 'V-3001'}])
    req('POST', f"/batches/{b4['id']}/verify", token=auditor)
    s, run1 = req('POST', '/runs', {'period': '2025-08'}, token=analyst)
    r1 = wait_run(admin, run1['id'])
    res1 = [r for r in r1['results'] if r['energyType'] == 'PURCH_STEEL' and r['plantId'] == 2][0]
    check('S9 首次运行采用 50t 且状态 OK',
          res1['status'] == 'OK' and float(res1['activityQty']) == 50.0, str(res1))
    s, binds1 = req('GET', f"/evidence/runs/{run1['id']}/bindings", token=admin)
    bind_v3001 = next(b for b in binds1 if b['voucherNo'] == 'V-3001')
    chain1 = json.loads(bind_v3001['evidenceChain'])
    hash_v1 = next(e['docHash'] for e in chain1 if e['docNo'] == 'ED-V-3001')
    check('S9 运行快照冻结声明 v1 哈希', hash_v1 == ed1['fileHash'], f"{hash_v1} vs {ed1['fileHash']}")
    # 供应商重发声明 v2：48t + 新哈希
    ed2 = doc(analyst, 'ED-V-3001', 'ENV_DECLARATION', 2, '2025-08', 'V-3001',
              'PO-2025-08-01', 'DN-301', 48, tier=2, hash_tag='decl-v2')
    check('S9 重发声明新建版本且哈希变化',
          ed2['versionNo'] == 2 and ed2['fileHash'] != ed1['fileHash'], str(ed2))
    adj9_pending = find_adj(admin, '2025-08', 'V-3001', status='PENDING')
    adj9_old = find_adj(admin, '2025-08', 'V-3001', status='INVALID')
    check('S9 旧裁定失效、新裁定待裁定（50 发票 vs 48 声明）',
          adj9_pending is not None and adj9_old is not None,
          f"pending={adj9_pending}, invalid={adj9_old}")
    # 旧运行快照不变
    s, binds1b = req('GET', f"/evidence/runs/{run1['id']}/bindings", token=admin)
    bind_old = next(b for b in binds1b if b['voucherNo'] == 'V-3001')
    check('S9 重发后旧运行快照仍冻结 v1 哈希与 50t',
          bind_old['adjudicationId'] == bind_v3001['adjudicationId']
          and float(bind_old['adoptedQty']) == 50.0
          and sha('ED-V-3001|decl-v1') in bind_old['evidenceChain'], str(bind_old['adoptedQty']))
    # 新运行待证并阻断披露
    s, run2 = req('POST', '/runs', {'period': '2025-08'}, token=analyst)
    r2 = wait_run(admin, run2['id'])
    res2 = [r for r in r2['results'] if r['energyType'] == 'PURCH_STEEL' and r['plantId'] == 2][0]
    check('S9 重裁前新运行 PENDING_EVIDENCE 且活动量为空',
          res2['status'] == 'PENDING_EVIDENCE' and res2['activityQty'] is None, str(res2))
    s, rep8 = req('POST', '/reports',
                  {'title': '2025年8月披露报告', 'period': '2025-08', 'runId': run2['id']},
                  token=analyst)
    s, _ = req('POST', f"/reports/{rep8['id']}/publish", token=finance)
    check('S9 待证报告发布被拒(409)', s == 409, f's={s}')
    # 人工裁定 48t 后新运行恢复正常并可发布
    s, decided9 = req('POST', f"/evidence/adjudications/{adj9_pending['id']}/decide",
                      {'adoptedQty': 48, 'reason': '供应商重发环境声明修正为 48t，发票差额为待开票调整，采用重发版本'},
                      token=auditor)
    check('S9 人工裁定重发数量 48t', s == 200 and float(decided9['adoptedQty']) == 48.0, str(b))
    s, run3 = req('POST', '/runs', {'period': '2025-08'}, token=analyst)
    r3 = wait_run(admin, run3['id'])
    res3 = [r for r in r3['results'] if r['energyType'] == 'PURCH_STEEL' and r['plantId'] == 2][0]
    check('S9 重裁后新运行采用 48t',
          res3['status'] == 'OK' and float(res3['activityQty']) == 48.0, str(res3))
    s, ver = req('POST', f"/reports/{rep8['id']}/freeze",
                 {'runId': run3['id'], 'note': '按重发声明重裁后冻结'}, token=analyst)
    check('S9 重裁后冻结勾稽一致', s == 200 and ver['reconcileStatus'] == 'OK', str(ver))
    req('POST', f"/reports/{rep8['id']}/approve", token=finance)
    s, pub = req('POST', f"/reports/{rep8['id']}/publish", token=finance)
    check('S9 重裁后报告发布成功', s == 200 and pub['status'] == 'PUBLISHED', str(pub))

    # ========== 2025-07 全量解除待证后可发布 ==========
    print('\n[收尾] 2025-07 全部凭证裁定后运行与披露恢复')
    s, run7 = req('POST', '/runs', {'period': '2025-07'}, token=analyst)
    r7 = wait_run(admin, run7['id'])
    pending_left = [r for r in r7['results'] if r['status'] != 'OK']
    check('2025-07 重算后无待证/待核指标', len(pending_left) == 0,
          str([(r['status'], r['energyType']) for r in pending_left]))
    s, ver7 = req('POST', f"/reports/{rep7['id']}/freeze",
                  {'runId': run7['id'], 'note': '凭证全部裁定后冻结'}, token=analyst)
    check('2025-07 冻结勾稽一致', ver7['reconcileStatus'] == 'OK', str(ver7))
    req('POST', f"/reports/{rep7['id']}/approve", token=finance)
    s, pub7 = req('POST', f"/reports/{rep7['id']}/publish", token=finance)
    check('2025-07 报告发布成功', s == 200 and pub7['status'] == 'PUBLISHED', str(pub7))

    print('\n========================================')
    print(f'通过 {len(PASSED)} 项，失败 {len(FAILED)} 项')
    if FAILED:
        for name, detail in FAILED:
            print(f'  失败: {name} :: {detail}')
        sys.exit(1)
    print('凭证裁定专项验收 S6~S9 全部通过 ✔')


if __name__ == '__main__':
    main()

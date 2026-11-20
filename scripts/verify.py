#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
验收回放脚本：碳排放披露数据血缘与版本影响管理平台
回放场景：
  S0 基线：种子数据（2025-05 已发布报告）核对
  S1 来源补录 → 影响评估 → 重述建议 → 接受 → 重新发布（历史保留）
  S2 因子修订：运行中替换因子，当前运行仍绑定启动清单；新运行用新版
  S3 并发采购更正：已核算采购量不可覆盖；同批次并发修订冲突
  S4 勾稽不平：报告保持待核，审批/发布被拒；修复后放行
  S5 已发布报告发现来源缺失：生成重述建议与差异说明，不覆盖已发布报告
前置：后端已启动（默认 http://127.0.0.1:8080），数据库为初始种子状态。
"""
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


def login(username, password):
    s, b = req('POST', '/auth/login', {'username': username, 'password': password})
    if s != 200:
        print(f'登录失败 {username}: {b}')
        sys.exit(1)
    return b['token']


def wait_run(token, run_id, timeout=40):
    for _ in range(timeout * 2):
        s, b = req('GET', f'/runs/{run_id}', token=token)
        if s == 200 and b['run']['status'] in ('COMPLETED', 'FAILED'):
            return b['run']
        time.sleep(0.5)
    raise RuntimeError(f'运行 {run_id} 超时未完成')


def find_impact(token, trigger_type, period, ref_keyword=None):
    s, impacts = req('GET', '/impacts', token=token)
    for im in impacts:
        if im['triggerType'] == trigger_type and im['period'] == period:
            if ref_keyword and ref_keyword not in im['triggerRef']:
                continue
            return im
    return None


def report_of(token, period):
    s, reports = req('GET', '/reports', token=token)
    for r in reports:
        if r['period'] == period:
            return r
    return None


def evidence_doc(token, doc_no, doc_type, plant_id, period, voucher_no,
                 order_no, delivery_no, qty, unit='t', tier=1, signed=True,
                 material='PURCH_STEEL', density=None, pack_spec=None,
                 extract_status='OK'):
    """登记一张采购凭证（含文件哈希与抽取字段）。"""
    import hashlib
    fields = [
        {'name': 'QUANTITY', 'text': f'{qty}{unit}', 'numValue': qty,
         'unit': unit, 'extractStatus': extract_status},
        {'name': 'UNIT', 'text': unit, 'unit': unit, 'extractStatus': 'OK'}]
    if density is not None:
        fields.append({'name': 'DENSITY', 'numValue': density, 'unit': 't/m3',
                       'extractStatus': 'OK'})
    if pack_spec is not None:
        fields.append({'name': 'PACK_SPEC', 'numValue': pack_spec, 'unit': 'kg/PCS',
                       'extractStatus': 'OK'})
    body = {
        'docNo': doc_no, 'docType': doc_type, 'supplier': '验收回放供应商',
        'plantId': plant_id, 'period': period, 'voucherNo': voucher_no,
        'orderNo': order_no, 'deliveryNo': delivery_no, 'materialCode': material,
        'signed': signed, 'authorityTier': tier, 'docDate': period + '-10',
        'fileName': doc_no + '.pdf',
        'fileHash': hashlib.sha256((doc_no + '|v1').encode()).hexdigest(),
        'fileSize': 10240, 'fields': fields}
    s, body = req('POST', '/evidence/documents', body, token=token)
    assert s == 200, f'凭证登记失败 {doc_no}: {s} {body}'
    return s, body


def seed_three_docs(recv_token, adj_token, plant_id, period, voucher, order_no, delivery_no,
                    po_qty, inv_qty, weigh_qty, unit='t'):
    """登记 订单+发票+过磅单 三证（分析员接收）并触发裁定（审核员权限）。"""
    evidence_doc(recv_token, 'PO-' + voucher, 'PURCHASE_ORDER', plant_id, period, voucher,
                 order_no, None, po_qty, unit, tier=3, signed=False)
    evidence_doc(recv_token, 'INV-' + voucher, 'INVOICE', plant_id, period, voucher,
                 order_no, delivery_no, inv_qty, unit, tier=1)
    evidence_doc(recv_token, 'WN-' + voucher, 'WEIGHNOTE', plant_id, period, voucher,
                 order_no, delivery_no, weigh_qty, unit, tier=1)
    s, body = req('POST',
                  f'/evidence/adjudications/re-run?period={period}&plantId={plant_id}&voucherNo={voucher}',
                  token=adj_token)
    return body


def main():
    print(f'== 目标: {BASE}')
    admin = login('admin', 'admin123')
    analyst = login('analyst', 'analyst123')
    auditor = login('auditor', 'auditor123')
    finance = login('finance', 'finance123')

    # ---------- S0 基线 ----------
    print('\n[S0] 基线：种子数据核对')
    s, dash = req('GET', '/dashboard/summary', token=admin)
    pub = dash.get('latestPublished') or {}
    check('S0 已发布 2025-05 报告', pub.get('period') == '2025-05', str(pub))
    check('S0 披露总量 2503.18 tCO2e',
          pub and abs(float(pub['totalTco2e']) - 2503.18) < 0.01, str(pub.get('totalTco2e')))
    check('S0 范围一/二/三 = 70.92/917.26/1515',
          pub and abs(float(pub['totalScope1']) - 70.92) < 0.01
          and abs(float(pub['totalScope2']) - 917.26) < 0.01
          and abs(float(pub['totalScope3']) - 1515) < 0.01,
          str(pub))
    s, runs = req('GET', '/runs', token=admin)
    run_may = next((r for r in runs if r['period'] == '2025-05' and r['purpose'] == 'OFFICIAL'), None)
    check('S0 2025-05 正式运行已完成', run_may and run_may['status'] == 'COMPLETED')
    s, detail = req('GET', f"/runs/{run_may['id']}", token=admin)
    check('S0 运行绑定 6 个批准因子快照', len(detail['bindings']) == 6, str(len(detail['bindings'])))
    res0 = next(r for r in detail['results'] if r['energyType'] == 'GRID_ELEC' and r['plantId'] == 1
                and r['productLineId'] == 1)
    s, lin = req('GET', f"/results/{res0['id']}/lineage", token=admin)
    check('S0 血缘：结果可下钻到因子版本与来源行',
          lin['factorVersion']['factorCode'] == 'ELEC-CNEAST'
          and lin['sources'][0]['batchNo'] == 'B-2025-05-ELEC-SZ', json.dumps(lin)[:200])

    # ---------- S1 来源补录 ----------
    print('\n[S1] 来源补录 → 影响评估 → 重述')
    s, b = req('POST', '/batches', {
        'plantId': 1, 'category': 'FUEL', 'period': '2025-05',
        'rows': [{'productLineId': 1, 'energyType': 'DIESEL', 'quantity': 1500}]}, token=analyst)
    check('S1 补录批次已上传', s == 200, str(b))
    batch_s1 = b['id']
    s, b = req('POST', f'/batches/{batch_s1}/verify', token=auditor)
    check('S1 补录批次核验通过', s == 200 and b['status'] == 'VERIFIED', str(b))
    im = find_impact(admin, 'SOURCE_SUPPLEMENT', '2025-05')
    check('S1 自动生成来源补录影响评估', im is not None)
    s, imd = req('GET', f"/impacts/{im['id']}", token=admin)
    item = next((i for i in imd['items'] if i['scope'] == 'SCOPE1'), None)
    check('S1 影响指标：苏州/精密结构件/SCOPE1 +4.02',
          item and abs(float(item['diff']) - 4.02) < 0.001
          and abs(float(item['oldValue']) - 70.92) < 0.001, json.dumps(imd['items'])[:300])
    check('S1 影响项关联已发布报告', item and item['affectedReportId'] is not None)
    rest = imd['restatements'][0] if imd['restatements'] else None
    check('S1 自动生成重述建议(待处理)', rest and rest['status'] == 'PROPOSED', str(imd['restatements']))
    rep = report_of(admin, '2025-05')
    s, b = req('POST', f"/impacts/restatements/{rest['id']}/accept", token=finance)
    check('S1 接受重述 → 冻结 v2', s == 200 and b['newVersion']['versionNo'] == 2, str(b))
    s, b = req('POST', f"/reports/{rep['id']}/approve", token=finance)
    check('S1 重述版本审批通过', s == 200 and b['status'] == 'APPROVED', str(b))
    s, b = req('POST', f"/reports/{rep['id']}/publish", token=finance)
    check('S1 重述版本发布', s == 200 and b['status'] == 'PUBLISHED', str(b))
    s, rd = req('GET', f"/reports/{rep['id']}", token=admin)
    v1, v2 = rd['versions'][1], rd['versions'][0]
    check('S1 披露历史：v1 已重述 / v2 已发布',
          v1['status'] == 'RESTATED' and v2['status'] == 'PUBLISHED',
          f"v1={v1['status']} v2={v2['status']}")
    check('S1 v2 范围一 = 74.94（70.92+4.02）',
          abs(float(v2['totalScope1']) - 74.94) < 0.01, str(v2['totalScope1']))

    # ---------- S2 因子修订（运行中替换） ----------
    print('\n[S2] 因子修订：运行中替换因子仍绑定启动清单')
    s, run1 = req('POST', '/runs', {'period': '2025-06'}, token=analyst)
    check('S2 2025-06 正式运行启动', s == 200 and run1['status'] == 'RUNNING', str(run1))
    s, factors = req('GET', '/factors', token=admin)
    elec_east = next(f for f in factors if f['factor']['code'] == 'ELEC-CNEAST')
    s, v2draft = req('POST', f"/factors/{elec_east['factor']['id']}/versions",
                     {'value': 0.5420, 'validFrom': '2025-01-01', 'validTo': '2025-12-31'},
                     token=analyst)
    check('S2 新版电力因子草稿创建', s == 200, str(v2draft))
    s, b = req('POST', f"/factors/versions/{v2draft['id']}/approve", token=auditor)
    check('S2 运行中批准 v2（替换 v1）', s == 200 and b['status'] == 'APPROVED', str(b))
    run1 = wait_run(admin, run1['id'])
    s, d1 = req('GET', f"/runs/{run1['id']}", token=admin)
    vals = {r['factorValue'] for r in d1['results'] if r['energyType'] == 'GRID_ELEC'
            and r['plantId'] == 1 and r['status'] == 'OK'}
    check('S2 运行中替换因子：本次运行仍用启动时 v1=0.5566',
          vals == {0.5566} or vals == {'0.5566'}, str(vals))
    bound = {bnd['factorCode']: bnd['value'] for bnd in d1['bindings']}
    check('S2 绑定清单快照保留 v1 值', float(bound.get('ELEC-CNEAST', 0)) == 0.5566, str(bound))
    s, run2 = req('POST', '/runs', {'period': '2025-06'}, token=analyst)
    run2 = wait_run(admin, run2['id'])
    s, d2 = req('GET', f"/runs/{run2['id']}", token=admin)
    vals2 = {r['factorValue'] for r in d2['results'] if r['energyType'] == 'GRID_ELEC'
             and r['plantId'] == 1 and r['status'] == 'OK'}
    check('S2 新运行使用替换后 v2=0.5420', vals2 == {0.542} or vals2 == {'0.542'}, str(vals2))
    im_f = find_impact(admin, 'FACTOR_REVISION', '2025-05')
    check('S2 因子修订触发对 2025-05 已发布报告的影响评估', im_f is not None)
    s, imfd = req('GET', f"/impacts/{im_f['id']}", token=admin)
    diff_s2 = sum(float(i['diff']) for i in imfd['items']
                  if i['scope'] == 'SCOPE2' and i['plantId'] == 1)
    check('S2 影响差异：苏州 SCOPE2 合计 -16.06（0.5566→0.5420 × 110万kWh）',
          abs(diff_s2 + 16.06) < 0.01, f'diff={diff_s2}')
    rest_f = imfd['restatements'][0] if imfd['restatements'] else None
    check('S2 生成重述建议', rest_f and rest_f['status'] == 'PROPOSED')
    # 接受因子重述 → v3
    s, b = req('POST', f"/impacts/restatements/{rest_f['id']}/accept", token=finance)
    check('S2 接受重述 → 冻结 v3', s == 200 and b['newVersion']['versionNo'] == 3, str(b))
    rep = report_of(admin, '2025-05')
    req('POST', f"/reports/{rep['id']}/approve", token=finance)
    s, b = req('POST', f"/reports/{rep['id']}/publish", token=finance)
    check('S2 v3 发布', s == 200 and b['status'] == 'PUBLISHED', str(b))

    # ---------- S3 并发采购更正 ----------
    print('\n[S3] 并发采购更正：已核算采购量不可覆盖')
    s, batches = req('GET', '/batches', token=admin, )
    proc = next(b for b in batches if b['batchNo'] == 'B-2025-06-PROC-GP')
    s, pd = req('GET', f"/batches/{proc['id']}", token=admin)
    check('S3 采购批次行已被核算消耗', len(pd['consumedRowIds']) == 2, str(pd['consumedRowIds']))
    # 3a: 修订试图改小/改大已消耗凭证数量 → 409
    s, rev1 = req('POST', f"/batches/{proc['id']}/revise", {
        'plantId': 1, 'category': 'PROCUREMENT', 'period': '2025-06',
        'rows': [
            {'plantId': 1, 'productLineId': 1, 'energyType': 'PURCH_STEEL', 'quantity': 520, 'voucherNo': 'V-1001'},
            {'plantId': 2, 'productLineId': 1, 'energyType': 'PURCH_STEEL', 'quantity': 300, 'voucherNo': 'V-1002'}]},
        token=analyst)
    check('S3 修订批次已提交(待核验)', s == 200, str(rev1))
    s, b = req('POST', f"/batches/{rev1['id']}/verify", token=auditor)
    check('S3 覆盖已核算采购量被拒绝(409)', s == 409, f's={s} {b}')
    s, b = req('GET', f"/batches/{rev1['id']}", token=admin)
    check('S3 违规修订标记为冲突', b['batch']['status'] == 'CONFLICT', str(b['batch']['status']))
    s, b = req('GET', f"/batches/{proc['id']}", token=admin)
    row_v1001 = next(r for r in b['rows'] if r['voucherNo'] == 'V-1001')
    check('S3 原采购量未被覆盖（V-1001 仍 500）',
          float(row_v1001['quantity']) == 500.0, str(row_v1001['quantity']))
    # 3b: 合规修订（不动已消耗行，新增凭证行）→ 通过
    s, rev2 = req('POST', f"/batches/{proc['id']}/revise", {
        'plantId': 1, 'category': 'PROCUREMENT', 'period': '2025-06',
        'rows': [
            {'plantId': 1, 'productLineId': 1, 'energyType': 'PURCH_STEEL', 'quantity': 500, 'voucherNo': 'V-1001'},
            {'plantId': 2, 'productLineId': 1, 'energyType': 'PURCH_STEEL', 'quantity': 300, 'voucherNo': 'V-1002'},
            {'plantId': 2, 'productLineId': 2, 'energyType': 'PURCH_STEEL', 'quantity': 80, 'voucherNo': 'V-1003'}]},
        token=analyst)
    s, b = req('POST', f"/batches/{rev2['id']}/verify", token=auditor)
    check('S3 合规修订核验通过', s == 200 and b['status'] == 'VERIFIED', str(b))
    s, b = req('GET', f"/batches/{proc['id']}", token=admin)
    check('S3 原批次被取代', b['batch']['status'] == 'SUPERSEDED', str(b['batch']['status']))
    # 3c: 同一批次并发修订 → 第二个 409
    cur = rev2['id']
    s, ra = req('POST', f"/batches/{cur}/revise", {
        'plantId': 1, 'category': 'PROCUREMENT', 'period': '2025-06',
        'rows': [
            {'plantId': 1, 'productLineId': 1, 'energyType': 'PURCH_STEEL', 'quantity': 500, 'voucherNo': 'V-1001'},
            {'plantId': 2, 'productLineId': 1, 'energyType': 'PURCH_STEEL', 'quantity': 300, 'voucherNo': 'V-1002'},
            {'plantId': 2, 'productLineId': 2, 'energyType': 'PURCH_STEEL', 'quantity': 80, 'voucherNo': 'V-1003'}]},
        token=analyst)
    check('S3 工厂A修订提交成功', s == 200, str(ra))
    s, rb = req('POST', f"/batches/{cur}/revise", {
        'plantId': 2, 'category': 'PROCUREMENT', 'period': '2025-06',
        'rows': [
            {'plantId': 1, 'productLineId': 1, 'energyType': 'PURCH_STEEL', 'quantity': 500, 'voucherNo': 'V-1001'},
            {'plantId': 2, 'productLineId': 1, 'energyType': 'PURCH_STEEL', 'quantity': 300, 'voucherNo': 'V-1002'},
            {'plantId': 2, 'productLineId': 2, 'energyType': 'PURCH_STEEL', 'quantity': 80, 'voucherNo': 'V-1003'}]},
        token=analyst)
    check('S3 工厂B并发修订同一批次被拒绝(409)', s == 409, f's={s} {rb}')
    req('POST', f"/batches/{ra['id']}/reject", {'reason': '清理演示数据'}, token=auditor)

    # ---------- S4 勾稽不平保持待核 ----------
    print('\n[S4] 勾稽不平：报告保持待核，不得归零')
    s, b = req('POST', '/reports', {
        'title': '2025年6月集团碳排放披露报告', 'period': '2025-06', 'runId': run2['id']},
        token=analyst)
    check('S4 2025-06 报告创建', s == 200, str(b))
    rep6 = b
    check('S4 报告保持待核(RECONCILE_FAILED)', b['status'] == 'RECONCILE_FAILED', str(b['status']))
    s, rd6 = req('GET', f"/reports/{rep6['id']}", token=admin)
    ver6 = rd6['versions'][0]
    check('S4 勾稽差异=未分摊公共用电 54.2 tCO2e',
          ver6['reconcileStatus'] == 'MISMATCH' and abs(float(ver6['reconcileDiff']) - 54.2) < 0.01,
          f"{ver6['reconcileStatus']} diff={ver6['reconcileDiff']}")
    check('S4 含 1 项缺因子待核指标（成都天然气）', ver6['pendingCount'] == 1, str(ver6['pendingCount']))
    s, b = req('POST', f"/reports/{rep6['id']}/approve", token=finance)
    check('S4 待核报告审批被拒绝(409)', s == 409, f's={s}')
    s, b = req('POST', f"/reports/{rep6['id']}/publish", token=finance)
    check('S4 待核报告发布被拒绝(409)', s == 409, f's={s}')
    # 修复1：批准西南天然气因子 → 消除待核指标
    s, factors = req('GET', '/factors', token=admin)
    ng_west = next(f for f in factors if f['factor']['code'] == 'NG-CNWEST')
    draft_v = next(v for v in ng_west['versions'] if v['status'] == 'DRAFT')
    s, b = req('POST', f"/factors/versions/{draft_v['id']}/approve", token=auditor)
    check('S4 西南天然气因子批准', s == 200, str(b))
    # 修复2：修订电力批次，把公共用电分摊到产品线
    elec6 = next(x for x in req('GET', '/batches', token=admin)[1]
                 if x['batchNo'] == 'B-2025-06-ELEC-SZ')
    s, rev_e = req('POST', f"/batches/{elec6['id']}/revise", {
        'plantId': 1, 'category': 'ELECTRICITY', 'period': '2025-06',
        'rows': [
            {'productLineId': 1, 'energyType': 'GRID_ELEC', 'quantity': 800000},
            {'productLineId': 2, 'energyType': 'GRID_ELEC', 'quantity': 500000}]},
        token=analyst)
    s, b = req('POST', f"/batches/{rev_e['id']}/verify", token=auditor)
    check('S4 公共用电分摊修订核验通过', s == 200 and b['status'] == 'VERIFIED', str(b))
    # 修复2.5：S3 新增采购凭证 V-1003 补录三证并完成数量裁定，解除待证阻断
    adj_v1003 = seed_three_docs(analyst, auditor, 2, '2025-06', 'V-1003',
                                'PO-2025-06-03', 'DN-103', 80, 80, 80)
    check('S4 V-1003 三证唯一解自动裁定 80t',
          adj_v1003['status'] == 'DECIDED' and float(adj_v1003['adoptedQty']) == 80.0,
          str(adj_v1003))
    # 修复3：重新运行并冻结新版本
    s, run3 = req('POST', '/runs', {'period': '2025-06'}, token=analyst)
    run3 = wait_run(admin, run3['id'])
    s, d3 = req('GET', f"/runs/{run3['id']}", token=admin)
    pend3 = [r for r in d3['results'] if r['status'] == 'PENDING_FACTOR']
    check('S4 重算后无待核指标', len(pend3) == 0, str(len(pend3)))
    s, b = req('POST', f"/reports/{rep6['id']}/freeze", {'runId': run3['id'], 'note': '修复后重冻结'},
               token=analyst)
    check('S4 修复后冻结 v2 勾稽一致', s == 200 and b['reconcileStatus'] == 'OK', str(b))
    s, b = req('POST', f"/reports/{rep6['id']}/approve", token=finance)
    check('S4 修复后审批通过', s == 200 and b['status'] == 'APPROVED', str(b))
    s, b = req('POST', f"/reports/{rep6['id']}/publish", token=finance)
    check('S4 修复后发布成功', s == 200 and b['status'] == 'PUBLISHED', str(b))

    # ---------- S5 已发布报告发现来源缺失 ----------
    print('\n[S5] 已发布报告发现来源缺失 → 重述建议，不覆盖已发布报告')
    s, rd = req('GET', f"/reports/{rep['id']}", token=admin)
    pub_v = rd['versions'][0]
    check('S5 当前发布版本 v3（范围一 74.94）',
          pub_v['versionNo'] == 3 and abs(float(pub_v['totalScope1']) - 74.94) < 0.01,
          f"v{pub_v['versionNo']} s1={pub_v['totalScope1']}")
    fuel_may = next(x for x in req('GET', '/batches', token=admin)[1]
                    if x['batchNo'] == 'B-2025-05-FUEL-SZ')
    s, b = req('POST', f"/batches/{fuel_may['id']}/void",
               {'reason': '审计发现电表/气表原始记录缺失'}, token=auditor)
    check('S5 来源批次作废', s == 200 and b['status'] == 'VOID', str(b))
    im_m = find_impact(admin, 'SOURCE_MISSING', '2025-05')
    check('S5 自动生成来源缺失影响评估', im_m is not None)
    s, immd = req('GET', f"/impacts/{im_m['id']}", token=admin)
    item_m = next((i for i in immd['items'] if i['scope'] == 'SCOPE1'), None)
    check('S5 差异说明：苏州 SCOPE1 -70.92（缺失燃料数据）',
          item_m and abs(float(item_m['diff']) + 70.92) < 0.01, json.dumps(immd['items'])[:300])
    rest_m = immd['restatements'][0] if immd['restatements'] else None
    check('S5 生成重述建议(待处理)', rest_m and rest_m['status'] == 'PROPOSED')
    # 已发布报告内容未被覆盖
    s, rd2 = req('GET', f"/reports/{rep['id']}", token=admin)
    still = next(v for v in rd2['versions'] if v['id'] == pub_v['id'])
    check('S5 已发布 v3 内容未被覆盖（仍 74.94 / PUBLISHED）',
          still['status'] == 'PUBLISHED' and abs(float(still['totalScope1']) - 74.94) < 0.01,
          f"{still['status']} {still['totalScope1']}")
    s, b = req('POST', f"/impacts/restatements/{rest_m['id']}/accept", token=finance)
    check('S5 接受重述 → 冻结 v4', s == 200 and b['newVersion']['versionNo'] == 4, str(b))
    req('POST', f"/reports/{rep['id']}/approve", token=finance)
    s, b = req('POST', f"/reports/{rep['id']}/publish", token=finance)
    check('S5 v4 发布', s == 200 and b['status'] == 'PUBLISHED', str(b))
    s, rd3 = req('GET', f"/reports/{rep['id']}", token=admin)
    statuses = {v['versionNo']: v['status'] for v in rd3['versions']}
    v4 = rd3['versions'][0]
    check('S5 披露历史完整：v1-v3 已重述，v4 已发布',
          statuses.get(1) == 'RESTATED' and statuses.get(2) == 'RESTATED'
          and statuses.get(3) == 'RESTATED' and statuses.get(4) == 'PUBLISHED', str(statuses))
    check('S5 v4 范围一 = 4.02（仅剩补录柴油）',
          abs(float(v4['totalScope1']) - 4.02) < 0.01, str(v4['totalScope1']))

    # ---------- 汇总 ----------
    print('\n========================================')
    print(f'通过 {len(PASSED)} 项，失败 {len(FAILED)} 项')
    if FAILED:
        for name, detail in FAILED:
            print(f'  失败: {name} :: {detail}')
        sys.exit(1)
    print('全部验收场景回放通过 ✔')


if __name__ == '__main__':
    main()

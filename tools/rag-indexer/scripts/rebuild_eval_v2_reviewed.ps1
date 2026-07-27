$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$datasetPath = Join-Path $root 'corpus\model_y_2026_refresh_trial\evaluation_parent_evidence_v2.json'
$reviewPath = Join-Path $root 'corpus\model_y_2026_refresh_trial\work\eval-v2-review.json'
$backupPath = Join-Path $root 'corpus\model_y_2026_refresh_trial\work\evaluation_parent_evidence_v2_before_review.json'

if (-not (Test-Path $reviewPath)) {
    throw "缺少审核结果：$reviewPath"
}
if (-not (Test-Path $backupPath)) {
    Copy-Item -LiteralPath $datasetPath -Destination $backupPath
}

$dataset = Get-Content -LiteralPath $datasetPath -Raw -Encoding UTF8 | ConvertFrom-Json
$review = Get-Content -LiteralPath $reviewPath -Raw -Encoding UTF8 | ConvertFrom-Json

function New-EvidenceSet($parentIds, $rationale) {
    [pscustomobject]@{
        requiredParentIds = @($parentIds)
        optionalLocatorChildIds = @()
        rationale = $rationale
    }
}

function New-Case($id, $query, $category, $criteria, $parentSets, $tags, $split = 'TEST') {
    [pscustomobject]@{
        caseId = $id
        query = $query
        category = $category
        answerability = 'ANSWERABLE'
        answerCriteria = @($criteria)
        acceptableEvidenceSets = @($parentSets)
        tags = @($tags)
        split = $split
    }
}

$removed = @{
    'battery-and-drive-unit-warranty' = $true
    'remote-climate-control-in-app' = $true
    'diy-service-appointment' = $true
    'safety-passenger-airbag' = $true
    'safety-warranty-airbag' = $true
    'climate-app-temperature' = $true
    'climate-app-toggle' = $true
    'charge-port-light-color' = $true
    'air-filter-interval' = $true
    'air-filter-mainland' = $true
    'tire-pressure-recommended' = $true
    'roof-rack-approved' = $true
    'service-appointment-app' = $true
    'hepa-filter-interval' = $true
    'out-of-scope-battery-capacity' = $true
    'out-of-scope-insurance' = $true
    'out-of-scope-charging-price' = $true
    'warranty-basic-scope' = $true
    'warranty-airbag-system' = $true
    'safety-child-airbag-state' = $true
    'wheel-nut-cap-tool' = $true
    'wheel-nut-cap-install' = $true
    'tire-pressure-menu' = $true
    'wiper-size' = $true
    'door-handle-wd40' = $true
    'bluetooth-pairing' = $true
    'phone-app-unlock' = $true
    'phone-app-start' = $true
    'headrest-remove' = $true
    'beijing-service-phone' = $true
    'beijing-service-address' = $true
    'shanghai-service-phone' = $true
    'shanghai-service-address' = $true
}

$cases = [System.Collections.Generic.List[object]]::new()
foreach ($case in $dataset.cases) {
    if ($removed.ContainsKey($case.caseId)) { continue }
    $reviewItem = $review.reviews.psobject.Properties[$case.caseId].Value
    $copy = $case | ConvertTo-Json -Depth 20 | ConvertFrom-Json
    if ($case.caseId -eq 'basic-vehicle-warranty') {
        $copy.query = '车辆基本有限质量保证是什么？'
        $copy.answerCriteria = @('说明车辆基本有限质量保证的覆盖范围、期限和主要限制')
    }
    if ($case.caseId -eq 'seatbelt-and-airbag-warranty') {
        $copy.query = '座椅安全带和气囊系统有限质量保证是什么？'
        $copy.answerCriteria = @('说明座椅安全带和气囊系统有限质量保证的覆盖范围和期限')
    }
    if ($case.caseId -eq 'diy-cabin-air-filter-cn-interval') {
        $copy.query = '中国大陆 Model Y 空调滤清器建议多久更换一次？'
        $copy.answerCriteria = @('只给出中国大陆空调滤清器建议更换时间或周期')
        $copy.acceptableEvidenceSets = @(New-EvidenceSet @('40607d84979a97c3c36aa9614d8729ecb0f51581535211446519984002ee31bf') '空调滤清器小节直接给出中国大陆更换时间')
    }
    if ($case.caseId -eq 'warranty-battery-duration') {
        $copy.query = '电池和驱动单元的质量保证期限是多少？'
        $copy.answerCriteria = @('给出电池和驱动单元质量保证期限，不能只返回章节标题')
        $copy.acceptableEvidenceSets = @(New-EvidenceSet @('a83b577d061ae437dbc2444298500c69ed5668d7672af72cca4ad19fe1e67c2a','b318e91899aa6ed89ae367449c6bba7fac56e0a1250d969f2a8122f9fac6d66d') '保证期限分布在同一主题的相邻 Parent，需要合并阅读')
    }
    if ($reviewItem -and $reviewItem.split) { $copy.split = $reviewItem.split }
    $cases.Add($copy)
}

# 每个新样本绑定一个主题明确的 Parent，避免把同一 Parent 拆成多个近义问题。
$extra = @(
    (New-Case 'safety-child-seat-installation' '儿童座椅的 ISOFIX/i-Size 约束系统如何安装？' 'SAFETY' '说明安装位置、锚点和固定要求' @(New-EvidenceSet @('3abbab790867b74fa3713965baa827a3a79793eeb132c040aadad70229acaa60') '儿童约束系统安装小节') @('PDF','安全','新增') 'DEV'),
    (New-Case 'charging-schedule' '如何设置 Model Y 的充电排程？' 'VEHICLE_OPERATION' '说明设置充电开始时间或计划充电的入口和步骤' @(New-EvidenceSet @('03158ba18260025d4622e0155367af6d761fd1f7410f903d628dee3655729e8b') '充电排程小节') @('PDF','充电','新增') 'DEV'),
    (New-Case 'charging-manual-release' '充电连接无法正常断开时，如何手动释放充电接口？' 'DIY_OPERATION' '说明手动释放充电连接的操作位置和注意事项' @(New-EvidenceSet @('18cc285c4d8c6cdad76449252100cfbfe78c736c3736c62e713ca5da75dee575') '充电接口手动释放小节') @('PDF','充电','新增') 'TEST'),
    (New-Case 'low-voltage-battery-replacement' 'Model Y 的低压电池何时需要更换？' 'DIY_OPERATION' '说明低压电池更换时机和操作前提' @(New-EvidenceSet @('016934a2364c9553bf91db067058b73cd2c46db847c8934989ed8db6e3af13f0') '低压电池维护小节') @('PDF','维护','新增') 'DEV'),
    (New-Case 'maintenance-recommendations' 'Model Y 有哪些定期维护保养建议？' 'DIY_OPERATION' '列出文档中明确的定期维护项目或检查建议' @(New-EvidenceSet @('40b60170c9482cffbfee23b5c4641c0d5565da28dcc9a6bf88e753a61b86ce63') '定期维护小节') @('PDF','维护','新增') 'TEST'),
    (New-Case 'tow-mode' '拖车或运输 Model Y 前如何启用拖车模式？' 'VEHICLE_OPERATION' '说明进入拖车模式的入口、启用条件和退出方式' @(New-EvidenceSet @('e29c65adc9dcef045d9c0da2fe345067540f69082d6678032716c949b67abd3c') '拖车模式小节') @('PDF','车辆操作','新增') 'DEV'),
    (New-Case 'dashcam-view-recordings' '如何使用行车记录仪查看和保存录像？' 'VEHICLE_OPERATION' '说明行车记录仪录像的查看或保存方法' @(New-EvidenceSet @('1c3e0f87bf43ca6467743315cd19b686716c18a98ad168d51724323f96e8d323') '行车记录仪小节') @('PDF','影像','新增') 'TEST'),
    (New-Case 'dashcam-usb-requirements' '用于行车记录仪的 USB 设备有什么要求？' 'DIY_OPERATION' '说明 USB 存储设备的格式或使用要求' @(New-EvidenceSet @('320b2b1fcf8265eb264ca21d0a29e7e185299bea4c6bec8486f87c7ab8d43119') 'USB 设备要求小节') @('PDF','影像','新增') 'TEST'),
    (New-Case 'software-update' 'Model Y 如何检查并安装软件更新？' 'VEHICLE_OPERATION' '说明检查更新、下载或安装更新的步骤和前提' @(New-EvidenceSet @('1145413c918a4a7efcabb411119d3f33183268b6257116b19ad821aa3982939b') '软件更新小节') @('PDF','软件','新增') 'DEV'),
    (New-Case 'driver-profile' '如何创建或切换 Model Y 的驾驶员档案？' 'VEHICLE_OPERATION' '说明创建、选择或切换驾驶员档案的入口' @(New-EvidenceSet @('08117c218014f6fb5ddbf4329a90e4ffec7b3781ff885b6b1400663395a38f47') '驾驶员档案小节') @('PDF','个性化','新增') 'TEST'),
    (New-Case 'key-card-use' '钥匙卡如何解锁和启动车辆？' 'VEHICLE_OPERATION' '说明使用钥匙卡解锁车辆和启动车辆的步骤' @(New-EvidenceSet @('375874553845b46ee4458f061894b57fc8a6952a9e1cf1a0775bd03148f9f412') '钥匙卡小节') @('PDF','钥匙','新增') 'DEV'),
    (New-Case 'phone-key-setup' '如何将手机设置为 Model Y 的钥匙？' 'VEHICLE_OPERATION' '说明手机钥匙的配对或启用步骤' @(New-EvidenceSet @('0cc31148ec66e94690c6682248650a3fafe955936be2a294fc26ca346b9d075b') '手机钥匙小节') @('PDF','钥匙','新增') 'TEST'),
    (New-Case 'cabin-camera-data' '车内摄像头用于什么，如何管理相关数据？' 'SAFETY' '说明车内摄像头用途以及相关数据管理说明' @(New-EvidenceSet @('371c13979d4a26252ac0bdc9fd89aea7ba7f78d27d53c3013568af2b48e611c0') '车内摄像头小节') @('PDF','隐私','新增') 'TEST'),
    (New-Case 'collision-warning-response' '车辆出现碰撞或制动警告时应如何处理？' 'SAFETY' '说明警告出现时驾驶员应采取的处理措施' @(New-EvidenceSet @('2bdc49882241776c4ee0f8d98e2769c47486fcf85c1234f3ecd05cd9b898c739') '故障或警告处理小节') @('PDF','安全','新增') 'DEV'),
    (New-Case 'lane-departure-warning' '车道偏离警告在什么情况下会触发？' 'SAFETY' '说明车道偏离警告的触发条件或限制' @(New-EvidenceSet @('1d356dad6e9c3ea0a4207bcfb49b0bf50bf67f24dc9b8432cb688afcacf8788f') '车道偏离警告小节') @('PDF','驾驶辅助','新增') 'TEST'),
    (New-Case 'front-camera-calibration' '前置摄像头需要在什么情况下校准？' 'SAFETY' '说明摄像头校准的触发条件、过程或完成提示' @(New-EvidenceSet @('3ef4357b3b6aeaf07c0e138896bce55435cb39bf155e2ad9343d99ee398bed06') '摄像头校准小节') @('PDF','驾驶辅助','新增') 'DEV'),
    (New-Case 'chengdu-service-center' '成都特斯拉服务中心（028-82074399）的地址是什么？' 'SERVICE_CENTER' '给出该服务中心的完整地址' @(New-EvidenceSet @('02976ed0f4702fa7da8e3a6fc34f4f05cbba108c560bf5e71c4e8d9dba3e26b0') '成都服务中心信息小节') @('HTML','服务中心','新增') 'TEST'),
    (New-Case 'hangzhou-service-center' '杭州特斯拉服务中心（0571-22935492）的地址是什么？' 'SERVICE_CENTER' '给出该服务中心的完整地址' @(New-EvidenceSet @('5f65786012dd34ce99829a3147b50939fc23fec8fda2bf2cb671d431374009c7') '杭州服务中心信息小节') @('HTML','服务中心','新增') 'TEST'),
    (New-Case 'haikou-service-center' '海口特斯拉服务中心（0898-36642279）的地址是什么？' 'SERVICE_CENTER' '给出该服务中心的完整地址' @(New-EvidenceSet @('68898643c7a7c6d8f6441cfdd11a520e594b7d49ff7352addaafe57bed2ce711') '海口服务中心信息小节') @('HTML','服务中心','新增') 'DEV'),
    (New-Case 'frunk-open' 'Model Y 的前备箱如何打开？' 'VEHICLE_OPERATION' '说明从车内屏幕或车外打开前备箱的方法' @(New-EvidenceSet @('672fad1678d4c1ce3331ea9f81235ea3a82ed1e479f774069961aa78959a9a9f') '前备箱操作小节') @('PDF','车辆操作','新增') 'TEST'),
    (New-Case 'door-open' '如何从车内打开 Model Y 的车门？' 'VEHICLE_OPERATION' '说明车内开门控制位置和操作方法' @(New-EvidenceSet @('4bfa27420567c59fc3dc79ff22553ba42fabc02f109683d2dbb887c1521eeffe') '车门操作小节') @('PDF','车辆操作','新增') 'DEV'),
    (New-Case 'coolant-check' '如何检查或补充 Model Y 的冷却液？' 'DIY_OPERATION' '说明冷却液检查、补充或禁止操作的要求' @(New-EvidenceSet @('54bf5d4a5a0faf26fee9bbb6fce70ba466f7bbbd50241c337bd100ae6a96daea') '冷却液小节') @('PDF','维护','新增') 'TEST'),
    (New-Case 'tire-repair-kit' 'Model Y 随车轮胎修理工具如何使用？' 'DIY_OPERATION' '说明轮胎修理工具的适用场景和基本使用步骤' @(New-EvidenceSet @('01d82ca5ff096d2b0a9db9cb70065fb6740aea401ebb76d55a53b304707b838b') '轮胎修理工具小节') @('PDF','轮胎','新增') 'DEV'),
    (New-Case 'vehicle-data-collection' '车辆会收集哪些驾驶和车辆数据？' 'OTHER' '概括文档中说明的车辆或驾驶数据采集范围' @(New-EvidenceSet @('1693bc357c889040eaed905e63cb7541b6feea97ecba2ff4c47bab8ea106a8a9') '车辆数据与隐私小节') @('PDF','隐私','新增') 'TEST'),
    (New-Case 'edr-purpose' 'Model Y 的事件数据记录器 EDR 记录什么信息？' 'OTHER' '说明 EDR 的用途以及可能记录的数据类型' @(New-EvidenceSet @('2661de543c9cd1ff9e4883d8a76d2493343ecbd3bb3a98a04fea2d2294eabefa') 'EDR 小节') @('PDF','数据','新增') 'DEV'),
    (New-Case 'wall-connector-safety' '使用 Tesla Wall Connector 充电前需要注意什么？' 'DIY_OPERATION' '列出 Wall Connector 使用前的安全或安装前提' @(New-EvidenceSet @('04d755507b0d900447f05f6a7ceb160aebbfeaf2135c05932362727fe586ae77') 'Wall Connector 小节') @('PDF','充电','新增') 'TEST'),
    (New-Case 'power-off' '如何关闭 Model Y 的车辆电源？' 'VEHICLE_OPERATION' '说明车辆关闭电源的入口和操作步骤' @(New-EvidenceSet @('ed7a754cde0e9b4c02c2eb6dc788c6ca3f7cf913b545733ff2dbf0173323803d') '关闭电源小节') @('PDF','车辆操作','新增') 'DEV'),
    (New-Case 'outside-mirror-adjustment' '如何调节 Model Y 的外后视镜？' 'VEHICLE_OPERATION' '说明调节左右外后视镜的位置和步骤' @(New-EvidenceSet @('d7d47be054d8f0ea0ba4bab6dcc982656cb92944637095c60f96a75c325289aa') '外后视镜调节小节') @('PDF','车辆操作','新增') 'TEST'),
    (New-Case 'pre-drive-check' 'Model Y 驾驶前应完成哪些检查？' 'SAFETY' '列出驾驶前需要确认的关键项目' @(New-EvidenceSet @('17545d9c45238e723708e34415eafb1d5b23fbf82933579b67324337b2f681e8') '驾驶前检查小节') @('PDF','安全','新增') 'DEV'),
    (New-Case 'camp-mode' '如何启用 Model Y 的露营模式？' 'VEHICLE_OPERATION' '说明露营模式的开启入口和主要作用' @(New-EvidenceSet @('2e64b63be43460c8b256f23c2e40025e747d6c1440ad142c7ad066a9555ecdae') '露营模式小节') @('PDF','车辆操作','新增') 'TEST'),
    (New-Case 'front-bumper-trim' 'Model Y 前保险杠饰板如何拆卸和安装？' 'DIY_OPERATION' '说明拆卸和安装前保险杠饰板的主要步骤' @(New-EvidenceSet @('2e00ec3275054ba1c2c7952ae92c37a45e0d1450d9ef32eadf73683f58fc4ebc') '前保险杠饰板操作小节') @('PDF','DIY','新增') 'TEST'),
    (New-Case 'charging-port-status' '充电端口状态指示灯有哪些状态含义？' 'VEHICLE_OPERATION' '说明充电端口状态指示灯的主要状态含义' @(New-EvidenceSet @('4580e45fbf09dcdb4876d563fb19b174d0d3893dc80610f3e25a2d3bf50b3aad') '充电端口指示灯小节') @('PDF','充电','新增') 'DEV')
)
foreach ($case in $extra) { $cases.Add($case) }

$duplicateQueries = $cases | Group-Object query | Where-Object Count -gt 1
if ($duplicateQueries) { throw "生成结果仍有重复问题：$($duplicateQueries.Name -join ', ')" }
$duplicateIds = $cases | Group-Object caseId | Where-Object Count -gt 1
if ($duplicateIds) { throw "生成结果仍有重复 caseId：$($duplicateIds.Name -join ', ')" }

$result = [pscustomobject]@{
    schemaVersion = 2
    datasetVersion = 'model-y-2026-refresh-parent-evidence-v2-reviewed'
    knowledgeScopeId = $dataset.knowledgeScopeId
    cases = @($cases)
}
$result | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $datasetPath -Encoding UTF8
Write-Output "Rebuilt Eval V2: $($cases.Count) cases"
$cases | Group-Object category | Sort-Object Name | ForEach-Object { Write-Output ("{0}: {1}" -f $_.Name, $_.Count) }

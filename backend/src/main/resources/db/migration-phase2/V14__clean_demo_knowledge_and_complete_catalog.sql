-- 清理历史自动验收残留，避免“缺类型、无图验证、知识库验收”等技术数据出现在业务页面。
CREATE TEMPORARY TABLE tmp_test_knowledge_ids (article_id BIGINT PRIMARY KEY);
INSERT INTO tmp_test_knowledge_ids(article_id)
SELECT article_id FROM p2_knowledge_article
WHERE title LIKE '知识库验收-%' OR title = '缺类型与无图验证';

DELETE ka FROM p2_knowledge_attachment ka JOIN tmp_test_knowledge_ids t ON t.article_id=ka.article_id;
DELETE a FROM p2_attachment a JOIN tmp_test_knowledge_ids t ON t.article_id=a.article_id;
DELETE c FROM p2_change_log c JOIN tmp_test_knowledge_ids t
  ON c.object_type='KNOWLEDGE_ARTICLE' AND c.object_id=CAST(t.article_id AS CHAR);
DELETE a FROM p2_knowledge_article a JOIN tmp_test_knowledge_ids t ON t.article_id=a.article_id;
DROP TEMPORARY TABLE tmp_test_knowledge_ids;

-- 补齐现场演示设备的类型、位置、管理状态和责任班组；不虚构厂家、序列号和日期。
INSERT INTO p2_equipment_profile
  (equipment_id,type_code,installation_location,management_status,technical_parameters,version,created_at,updated_at)
SELECT e.equipment_id,x.type_code,x.location,'IN_USE',JSON_ARRAY(JSON_OBJECT(
  'key','archiveStatus','label','资料状态','valueType','STRING',
  'value','基础档案已补齐，详细铭牌参数以现场受控资料为准','unitLabel',NULL
)),1,NOW(3),NOW(3)
FROM dim_equipment e
JOIN (
  SELECT 'SMELT-FUR-01' code,'MELTING_FURNACE' type_code,'车间1 · 熔炼区' location
  UNION ALL SELECT 'HOLD-FUR-01','HOLDING_FURNACE','车间1 · 保温区'
  UNION ALL SELECT 'CAST-01','CASTING_TRACTION','车间1 · 连铸牵引区'
  UNION ALL SELECT 'ROLL-01','ROLLING_MILL','车间1 · 连轧区'
  UNION ALL SELECT 'DRAW-01','DRAWING_MACHINE','车间2 · 拉丝生产区'
  UNION ALL SELECT 'DRAW-02','DRAWING_MACHINE','车间2 · 拉丝生产区'
  UNION ALL SELECT 'DRAW-03','DRAWING_MACHINE','车间2 · 拉丝生产区'
  UNION ALL SELECT 'GAUGE-02','DIAMETER_GAUGE','车间2 · 在线检测区'
) x ON x.code=e.equipment_code
ON DUPLICATE KEY UPDATE type_code=VALUES(type_code),installation_location=VALUES(installation_location),
  management_status='IN_USE',updated_at=NOW(3),version=version;

UPDATE dim_equipment SET responsible_person = CASE equipment_code
  WHEN 'SMELT-FUR-01' THEN '熔炼运行班组' WHEN 'HOLD-FUR-01' THEN '熔炼运行班组'
  WHEN 'CAST-01' THEN '连铸运行班组' WHEN 'ROLL-01' THEN '连轸运行班组'
  WHEN 'DRAW-01' THEN '拉丝运行班组' WHEN 'DRAW-02' THEN '拉丝运行班组'
  WHEN 'DRAW-03' THEN '拉丝运行班组' WHEN 'GAUGE-02' THEN '质量检测班组'
  ELSE responsible_person END
WHERE equipment_code IN ('SMELT-FUR-01','HOLD-FUR-01','CAST-01','ROLL-01','DRAW-01','DRAW-02','DRAW-03','GAUGE-02');

-- 隐藏仅用于接口验收的临时设备，知识库选择器也会主动排除该编码。
UPDATE dim_equipment SET enabled=0 WHERE equipment_code='KNOWLEDGE-VERIFY';

-- 为每种真实设备类型补充典型知识，并使五个知识分类均有多篇可阅读内容。
INSERT INTO p2_knowledge_article
  (title,category,equipment_id,equipment_type,fault_phenomenon,content,steps,source_repair_order_id,created_at,updated_at)
SELECT x.title,x.category,e.equipment_id,NULL,x.fault,x.content,x.steps,NULL,NOW(3),NOW(3)
FROM dim_equipment e JOIN (
  SELECT 'SMELT-FUR-01' code,'熔化炉交接班操作要点' title,'OPERATION_MAINTENANCE' category,NULL fault,
    '<p><strong>交接重点：</strong>核对炉温、铜液液位、炉压、燃气流量及未闭环异常，确认炉门、燃烧器和冷却系统状态。</p><p>任何设定值调整均应记录原因、调整前后参数及确认人。</p>' content,
    JSON_ARRAY('查阅上一班运行记录和未闭环异常。','核对温度、液位、炉压及燃气流量趋势。','现场检查炉门密封、燃烧火焰和冷却管路。','双方确认生产状态、注意事项和限制条件后完成交接。') steps
  UNION ALL SELECT 'SMELT-FUR-01','熔化炉燃烧不稳定的常见原因','COMMON_FAULT','火焰波动、炉压异常或燃气流量明显变化',
    '<p>常见原因包括燃气压力波动、空燃配比异常、燃烧器积灰或堵塞、炉门密封不良，以及炉压测点漂移。</p><p>出现回火、熄火或燃气报警时，应立即执行现场燃气安全处置规程。</p>',
    JSON_ARRAY('确认报警和联锁状态，不得强行复位。','核对燃气压力、流量和炉压趋势。','检查炉门密封及燃烧器外观。','由授权人员完成燃烧系统检查和恢复确认。')
  UNION ALL SELECT 'SMELT-FUR-01','熔化炉热电偶更换维修案例','REPAIR_CASE','单点温度长期固定且与相邻测点偏差持续扩大',
    '<p><strong>案例结论：</strong>经趋势比对和现场复测，确认测温元件响应失真。执行停炉隔离后更换同规格热电偶，复核安装深度与补偿导线，试运行后多点温差恢复合理。</p><p>经验：更换前应先排除接线松动和采集通道故障，避免误换元件。</p>',
    JSON_ARRAY('记录异常趋势并与便携测温结果比对。','执行停炉、降温和能源隔离。','检查接线与补偿导线后更换故障热电偶。','恢复后核对响应速度、温差和采集稳定性。')
  UNION ALL SELECT 'HOLD-FUR-01','保温炉交接与日常保养','OPERATION_MAINTENANCE',NULL,
    '<p>重点关注铜液温度、液位、保温时间、炉体密封和表面结渣。清理作业必须满足高温作业许可和防护要求。</p>',
    JSON_ARRAY('核对温度与液位趋势。','检查炉门、炉体密封及周边泄漏迹象。','确认上游供液与下游牵引节拍匹配。','按计划记录炉衬和结渣状态。')
  UNION ALL SELECT 'HOLD-FUR-01','保温炉温度波动的常见原因','COMMON_FAULT','铜液温度超出正常波动范围或升降温响应异常',
    '<p>应联合检查熔化炉供液节拍、保温功率、液位变化、炉门开启频次和测温元件，不应只通过提高设定值处理。</p>',
    JSON_ARRAY('核对异常时段的进液、出液和液位变化。','检查炉门密封与保温功率。','比对测温点与便携测温结果。','由工艺人员确认调整措施并跟踪至少一个生产周期。')
  UNION ALL SELECT 'CAST-01','结晶牵引机设备介绍','INTRODUCTION',NULL,
    '<p>结晶牵引机承接保温炉铜液，通过结晶器冷却和周期牵引形成连续铜坯，是铜液转化为可轧制固态坯料的关键设备。</p><ul><li>重点参数：冷却水、牵引速度、结晶状态</li><li>重点部位：结晶器、牵引轮、传动与防护</li></ul>',JSON_ARRAY()
  UNION ALL SELECT 'CAST-01','结晶铜坯表面裂纹的常见原因','COMMON_FAULT','铜坯出现纵向或横向裂纹、周期性振痕加重',
    '<p>常见原因包括冷却不均、结晶器状态异常、牵引节拍波动、铜液温度不稳定或结晶器安装偏心。</p>',
    JSON_ARRAY('记录裂纹位置、方向和周期特征。','核对冷却水及牵引速度趋势。','检查结晶器安装、磨损和冷却均匀性。','调整后低速验证并保留首件样品。')
  UNION ALL SELECT 'ROLL-01','多道轧机组设备介绍','INTRODUCTION',NULL,
    '<p>多道轧机组将结晶牵引形成的铜坯连续减径并整形成约Φ8 mm铜杆。产品尺寸和表面质量受轧辊、导卫、张力、润滑冷却与机架同步影响。</p>',JSON_ARRAY()
  UNION ALL SELECT 'ROLL-01','铜杆出口线径波动的常见原因','COMMON_FAULT','出口线径连续波动、椭圆度增大或表面出现周期性压痕',
    '<p>优先检查轧辊磨损、辊缝变化、导卫偏移、机架速度同步和来料尺寸。在线测量异常也可能造成误判，应与离线量具交叉确认。</p>',
    JSON_ARRAY('保留异常段样品并完成离线复测。','检查轧辊、导卫和紧固状态。','核对机架速度与负载趋势。','调整后进行首件确认。')
  UNION ALL SELECT 'DRAW-01','拉丝机设备与工艺作用','INTRODUCTION',NULL,
    '<p>拉丝机以Φ8 mm铜杆为来料，通过多道次模具逐步减径，生产客户要求的细铜丝。关键控制对象包括道次配比、模具、线速度、张力、润滑冷却和收放线同步。</p>',JSON_ARRAY()
  UNION ALL SELECT 'DRAW-01','拉丝机开机与换模操作要点','OPERATION_MAINTENANCE',NULL,
    '<p>换模和穿线必须在停机、卸载张力并确认旋转部件停止后进行。模具规格、方向和道次组合应与受控工艺单一致。</p>',
    JSON_ARRAY('确认生产规格、来料和模具组合。','停机隔离后检查模具、导轮和穿线路径。','完成穿线并恢复全部防护。','低速试机，复核张力、线速度和出线线径。','首件确认合格后逐步升至生产速度。')
  UNION ALL SELECT 'DRAW-03','拉丝机断丝维修案例','REPAIR_CASE','同一道次连续断丝，润滑正常但断点位置高度一致',
    '<p><strong>案例结论：</strong>检查发现对应拉丝模入口局部破损并伴有粘铜。更换模具、清理模盒并重新穿线后，低速和生产速度验证均未再断丝。</p><p>经验：断点位置重复时，应优先检查对应道次模具和导轮，而不是直接提高润滑浓度。</p>',
    JSON_ARRAY('记录断点位置并执行停机隔离。','检查对应道次模具和导轮。','更换损坏模具并清理模盒。','低速试机后复核线径、张力和表面质量。')
  UNION ALL SELECT 'GAUGE-02','在线测径仪设备介绍','INTRODUCTION',NULL,
    '<p>在线测径仪用于连续监测铜丝外径，为操作人员调整拉丝工艺和判断尺寸稳定性提供依据。其结果需定期与合格的离线量具和标准件比对。</p>',JSON_ARRAY()
  UNION ALL SELECT 'GAUGE-02','在线测径仪清洁与校准要点','OPERATION_MAINTENANCE',NULL,
    '<p>清洁测量窗口时应使用设备说明书规定的材料，避免划伤镜片。校准标准件应在有效期内并保持清洁。</p>',
    JSON_ARRAY('停用相关自动调节功能并记录当前读数。','检查和清洁测量窗口。','检查支架、中心位置和线材路径。','使用标准件完成零点与跨度核验。','恢复后与离线量具结果进行比对。')
  UNION ALL SELECT 'GAUGE-02','测径仪通讯中断维修案例','REPAIR_CASE','测径仪本地显示正常，但上位系统无数据或数据间歇中断',
    '<p><strong>案例结论：</strong>检查确认通讯接头松动并受现场振动影响。重新制作接头、固定线缆并连续观察后通讯恢复稳定。</p><p>经验：本地显示正常而平台无数据时，应先区分测量故障与通讯故障。</p>',
    JSON_ARRAY('确认本地显示与平台数据差异。','检查供电、通讯指示和接头。','修复并固定通讯线缆。','连续观察数据更新时间和丢包情况。')
) x ON x.code=e.equipment_code
WHERE NOT EXISTS (SELECT 1 FROM p2_knowledge_article a WHERE a.title=x.title);

-- “其他生产设备”保留类型级通用知识，不绑定验收临时设备。
INSERT INTO p2_knowledge_article
  (title,category,equipment_id,equipment_type,fault_phenomenon,content,steps,source_repair_order_id,created_at,updated_at)
SELECT x.title,x.category,NULL,'OTHER',x.fault,x.content,x.steps,NULL,NOW(3),NOW(3)
FROM (
  SELECT '辅助生产设备通用介绍' title,'INTRODUCTION' category,NULL fault,
    '<p>本条适用于尚未单独分类的泵、风机、过滤、冷却和物料辅助设备。建档时应记录用途、安装位置、能源介质、关键部件和安全隔离点。</p>' content,JSON_ARRAY() steps
  UNION ALL SELECT '辅助生产设备通用点检','OPERATION_MAINTENANCE',NULL,
    '<p>通用点检应覆盖防护、紧固、润滑、温升、振动、泄漏、异响和电气连接；具体标准以设备说明书和现场点检表为准。</p>',
    JSON_ARRAY('确认设备名称、位置和当前运行状态。','检查防护、紧固、润滑和泄漏。','观察温升、振动、异响及电气连接。','记录异常并按权限安排处理。')
  UNION ALL SELECT '辅助设备异常停机常见原因','COMMON_FAULT','设备无法启动、运行中异常停机或保护动作',
    '<p>应依次检查急停与联锁、供电和控制回路、过载保护、机械卡阻及上游下游条件。严禁绕过保护强行启动。</p>',
    JSON_ARRAY('确认人员安全并记录报警信息。','检查急停、联锁和供电状态。','检查机械卡阻、负载和保护动作。','由授权人员处理后执行试运行确认。')
) x WHERE NOT EXISTS (SELECT 1 FROM p2_knowledge_article a WHERE a.title=x.title);

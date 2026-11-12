/**
 * 档案缺项字段的中文名。列表与详情抽屉共用一份，避免两处文案漂移。
 * 键名与后端 missingFields 返回的字段路径保持一致。
 */
export const FIELD_LABELS: Record<string, string> = {
  responsiblePerson: '责任人',
  'profile.typeCode': '设备类型',
  'profile.model': '型号',
  'profile.processId': '所属工序',
  'profile.installationLocation': '安装位置',
  'profile.manufacturer': '制造厂家',
  'profile.factorySerialNo': '出厂编号',
  'profile.purchasedOn': '购置日期',
  'profile.commissionedOn': '投用日期',
  'profile.expectedLifeYears': '预计使用年限',
  'profile.warrantyExpiresOn': '质保到期日',
  'profile.managementStatus': '管理状态',
  'profile.technicalParameters': '技术参数',
};

/**
 * 责任人是否仍是占位值。
 * 占位值不是可派单的人，界面按「未指定」呈现，避免看起来像已经填了人。
 */
export function isPlaceholderPerson(value: string | null): boolean {
  if (!value) return true;
  const trimmed = value.trim();
  return trimmed === '' || ['待指定', '待补', '待补充', '未知', '未指定'].includes(trimmed);
}

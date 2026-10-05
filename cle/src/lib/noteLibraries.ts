export const noteLibraries = ['综合学习', '英语学习', '计算机学习', '数学学习', '生物化学与医学']

export const noteTemplates: Record<string, { title: string; body: string }> = {
  '综合学习': { title: '学习记录', body: '## 学习目标\n\n\n## 关键概念\n\n- \n\n## 我的理解\n\n\n## 待解决的问题\n\n- \n\n## 参考来源\n\n' },
  '英语学习': { title: '英语学习记录', body: '## 今日材料\n\n来源：\n\n## 词汇与表达\n\n| 表达 | 语境中的含义 | 我的例句 |\n| --- | --- | --- |\n| observe | 观察 | We observe a change. |\n\n## 阅读 / 听力要点\n\n- \n\n## 英文复述\n\n\n## 下次复习\n\n' },
  '计算机学习': { title: '编程学习记录', body: '## 问题与目标\n\n\n## 原理\n\n\n## 代码示例\n\n```python\ndef total(values):\n    return sum(values)\n```\n\n## 测试与结果\n\n| 输入 | 预期 | 实际 |\n| --- | --- | --- |\n| [1, 2, 3] | 6 | 待验证 |\n\n## 复杂度与边界\n\n\n## 参考来源\n\n' },
  '数学学习': { title: '数学学习记录', body: '## 定义与前提\n\n\n## 直觉理解\n\n\n## 例题与推导\n\n1. \n\n## 反例与易错点\n\n\n## 复习问题\n\n' },
}

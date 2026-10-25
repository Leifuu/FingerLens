# FingerLens 产品上下文

## Register

product

## Users

面向研究 Android 设备差异、核验普通 App 采集链路的使用者。主要流程是采集或导入两份指纹，按模块查找、比较并复核证据。

## Product Purpose

在本地保留、展示和比较 Android App 可见的设备信息，支持旧 JSON 和原始文件下载。依据 README 与现有实现，单项差异不作为第三方 App 风控或 Root 判定。

## Brand Personality

现有产品是中文数据工作台，沿用 Ant Design 暗色组件与直接、具体的字段说明；没有额外确认的品牌人格要求。

## Anti-references

不引入营销页面式的展示流程，不用缺乏证据的风险结论替代原始状态，不通过格式化改写输入文件。

## Design Principles

- 原始证据、采集状态与解释保持可区分。
- 优先缩短查找差异的路径，长数据采用逐层展开。
- 新展示兼容旧数据、缺失字段、单侧条目和未知值。
- 复用既有 Ant Design 交互和布局。

## Accessibility & Inclusion

沿用现有中文与系统字体、键盘操作、可见焦点和减少动态效果支持。状态同时以文本呈现；尚未声明完成正式 WCAG 审计。

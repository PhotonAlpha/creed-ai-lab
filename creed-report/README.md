# 显示 environment 所有active的值
http://localhost:48080/report/api/environment
http://localhost:48080/report/environment

# 显示 environment 转为 properties 分格 和 yaml分格
http://localhost:48080/report/api/environment/rendered
http://localhost:48080/report/environment/rendered

# Environment Inspector — 需求与设计
完整的需求清单、设计决策与扩展指引见 skill：`.claude/skills/env-inspector/SKILL.md`
核心实现：`creed-report/src/main/java/com/creed/report/service/EnvironmentInspectionService.java`

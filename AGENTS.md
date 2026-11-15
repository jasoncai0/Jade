# Jade 项目记忆

本文件是 `java-coding-agent` 的项目级记忆，不是全局配置。

## 博客踩坑记录

- 项目踩坑日志：`docs/BLOG_MEMORY.md`。
- 已发布文章：`coding-agent-pitfalls`，线上地址 <https://www.jasoncai.site/posts/coding-agent-pitfalls.html>。
- 博客源码：`~/Documents/deepseek-harness/default-workspace/blog/content/posts/coding-agent-pitfalls/`。

## 后续工作要求

1. 在本项目中遇到新的 bug、误判或环境坑时，先按“症状 / 根因 / 修法 / 证据”追加到 `docs/BLOG_MEMORY.md`。
2. 判断这个坑是否对读者有价值；有价值就更新博客文章对应章节，必要时补图，并重新发布。
3. 发布前阅读 `~/.dsh/hosts/blog-jasoncai.site.md`，使用 `scripts/publish.sh`，不要手改服务器。
4. 不把项目记忆写入 `~/.dsh/AGENTS.md`、全局 skill 或其他全局位置。
5. 不把 `.env`、API key、数据库口令或 run 目录里的敏感内容写进博客或项目记忆。

如果本次无法发布博客，在 `docs/BLOG_MEMORY.md` 的待办里标明原因和待执行命令。

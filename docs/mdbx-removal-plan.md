# 移除 MDBX · 收敛到 KDBX + Bitwarden 双后端

> **本文档已被 [architecture-kdbx-bitwarden.md](./architecture-kdbx-bitwarden.md) 取代**，请参阅新版架构文档。
>
> 以下为历史记录，保留供参考。

## 历史决策

- 2026-08-01：确认移除 MDBX，保留 KDBX + Bitwarden
- 2026-08-01：进一步确认移除 BastionLocal，彻底只留 KDBX + Bitwarden
- 2026-08-01：Phase 0（CI 基线闸门）完成，Phase 1a（MDBX 屏+导航入口）完成
- 2026-08-01：Phase A（整删 MDBX）开始执行，17 个 MDBX 专有文件已删除

## 完整架构设计

详见 [architecture-kdbx-bitwarden.md](./architecture-kdbx-bitwarden.md)

# 图标覆盖优化 · 中文服务品牌资产补全计划（交接文档）

> 状态：代码侧（A/B/C）已完成并提交到 `dev`；**中文品牌 PNG 图标资产尚未打包**，本文档用于其他 agent 接力补全。
> 关联改动：设置界面统一、首字母头像默认开启、favicon 多源兜底、自动匹配别名/包名/中文标题映射。

## 背景与根因

密码条目的图标解析链为：

```
自定义图标 → 上传图标 → 自动匹配 Stratum 图标(slug) → favicon(多源) → App 包图标 → 首字母头像兜底
```

排查结论（“很多图标没覆盖”的主因）：

1. **打包图标库对中国服务覆盖 ≈ 0**：`app/src/main/assets/stratum_icons/icons` + `extraicons` 共约 1078 个图标，抽查 27 个国内外常见服务，**所有中国服务（微信/支付宝/淘宝/京东/微博/百度/网易/QQ/B站/美团/滴滴/12306/银行/抖音/小红书等）均缺失**，仅有的 ✅ 全是海外品牌。
2. **favicon 兜底源是 `google.com/s2/favicons`**：国内网络下基本不可达/超时，导致有网址但无打包图标的条目最终全部落到“无图标”。
3. 自动匹配仅靠英文切词 + 极小的别名表（5 条），中文标题与 App 包名几乎无法命中。

## 本轮已完成（代码侧，已提交 dev）

- **A 首字母头像兜底（默认开启）**
  - 增强了 `UnmatchedIconFallback.WEBSITE_OR_TITLE_INITIAL`：**底色按文本哈希确定性取色**（`monogramColorFor`），每条目各不相同、稳定可辨识（天然 CJK 友好，中文直接取首字）。
  - 将默认策略由 `DEFAULT_ICON`（钥匙）改为 `WEBSITE_OR_TITLE_INITIAL`（`AppSettings` / `SettingsManager` 默认值 + `VaultV2Pane` 改为读取用户设置）。
  - 老列表 `PasswordListItem` 的 `DefaultKeyIcon` 也改为渲染首字母头像。
  - 该改动通过“默认策略翻转”一次性覆盖了 VaultV2、PasswordEntryCard、MultiPasswordEntryCard、TotpCodeCard、详情页、通行密钥页等所有调用 `UnmatchedIconFallback` 的界面。
- **B favicon 多源兜底**
  - `FaviconCache.getIcon` 改为顺序尝试 `DuckDuckGo → Google S2(64) → Google S2(128)`，缩短超时（3s/3.5s），任一源成功即缓存；全部失败最终落到首字母头像。
- **C 自动匹配映射扩充（代码侧）**
  - `DOMAIN_ALIAS_TO_ICON_SLUG`：新增全球服务域名变体 + 中国服务域名别名。
  - 新增 `PACKAGE_TO_ICON_SLUG`：Android 包名 → slug（覆盖 `com.spotify.music→spotify` 等，及中国 App 如 `com.tencent.mm→wechat`）。
  - 新增 `CJK_TITLE_TO_SLUG`：中文应用名 → slug（标题无法被英文切词命中）。
  - 上述映射已接入 `buildAutoMatchCandidates`；**只要目标 slug 的 PNG 资产存在，即可自动点亮对应图标**。

## 待补全：中文品牌 PNG 资产（本计划重点）

### 为什么还没做
中文品牌 logo 受商标/版权约束，且当前 sandbox 无法可靠取回合规资产，故本轮只完成了“接线”，未打包图片。资产就位后无需再改代码即可生效。

### 需要补充的 slug 清单（来自 C 中的映射，去重）
`wechat, weibo, alipay, taobao, tmall, jd, baidu, netease, bilibili, meituan, didi, 12306, icbc, cmbchina, ccb, abc, bankcomm, zhihu, douyin, xiaohongshu, kuaishou, dingtalk, feishu, tencent, pinduoduo`
（其中 `tencent/wechat/qq`、`netease/163/126`、`meituan/dianping/waimai`、`feishu/larksuite` 等为同一品牌不同入口，按品牌主 slug 打包即可。）

### 资产放置规范
与现有 Stratum 资产一致，放到：
```
app/src/main/assets/stratum_icons/icons/<slug>.png
app/src/main/assets/stratum_icons/icons/<slug>_dark.png   # 深色模式变体
```
- 建议尺寸：256×256 或 512×512（现有加载逻辑会按容器缩放）。
- 风格：尽量与现有 Stratum 图标一致（单色/品牌色字形，透明底）。现有 `fetchSimpleIconBitmap` 优先读取 `<slug>_dark.png`（深色）再回退 `<slug>.png`。

### 资产来源建议（需做版权合规确认）
- **simple-icons**（CC0，含大量中国品牌：wechat、weibo、alipay、taobao、baidu、bilibili、zhihu、douyin、xiaohongshu、jd、netease 等）→ 取 SVG 后栅格化为 PNG。
- 部分品牌（如 12306、各银行、钉钉、飞书）simple-icons 可能未收录，需从官方品牌资源或合规渠道获取并确认授权。
- 所有引入需记录来源与许可证，避免商标/版权风险。

### 验证方式
1. 将 PNG 放入上述目录（light + dark）。
2. 在代码中临时 `SimpleIconCatalog.getSlugs(context)` 打印，确认新 slug 已加载；或直接在 App 内新增对应条目（如标题“微信”、网址 `weixin.qq.com`、包名 `com.tencent.mm`），确认自动点亮真实图标。
3. 跑一次 GitHub Actions（Android CI debug）确保资产打包无误、无重复 slug 冲突。

## 备注
- 即便不补全中文资产，**首字母头像兜底（A）已保证所有条目都有可辨识、各不相同的图标**，体感覆盖问题已大幅缓解。
- 全球品牌若仍有缺失（如 `chrome/youtube/netflix/apple/telegram/whatsapp` 等），同样可按本规范补充对应 slug 的 PNG。

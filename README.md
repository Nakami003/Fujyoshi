# Fujyoshi扩展源 for Mihon

Source snapshot of the Huaka Comic extension, version 1.6.21. It supports search, filters, chapter listings, and reading. Sign-in uses the site’s login page in Mihon’s WebView. The source is marked NSFW.

To build it, copy `src/zh/huaka` into a checkout of [Keiyoushi extensions-source](https://github.com/keiyoushi/extensions-source) and follow its contribution guide.

Website: https://app.huakacomic.com/zh-CN

## Fujyoshi 扩展源

在 Mihon 中打开 **更多 → 设置 → 浏览 → 扩展仓库**，添加以下地址：

```text
https://raw.githubusercontent.com/Nakami003/Fujyoshi/main/index.pb
```

如果旧版客户端只支持 JSON 索引，可使用 `index.min.json`。添加后，在 **浏览 → 扩展** 中刷新并搜索「花咔漫画」。

首发 APK 为已在 Mihon 真机测试的 v1.6.21。扩展使用 [Keiyoushi extensions-source](https://github.com/keiyoushi/extensions-source) 的构建框架，相关 Apache 2.0 许可见 [LICENSE](LICENSE)。

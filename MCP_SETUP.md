# 配 GitHub MCP 给我 —— 手机端操作

配好之后我就能：建仓库 → 传源码 → 触发 Actions → 编出 APK。
你手机上点一下 Release 链接直接装。

---

## 一、先拿一个 GitHub Token

打开（手机浏览器就行）：

```
https://github.com/settings/tokens/new
```

填这几项：

| 项 | 填什么 |
|---|---|
| Note | `xhs-mcp` |
| Expiration | 30 天（够用，别设永久） |
| Select scopes | 勾 **`repo`**（整个大项）+ **`workflow`** |

> `workflow` 这个必须勾。不勾的话我往仓库里推 `.github/workflows/build.yml` 会被 GitHub 拒绝。
> `repo` 勾了会自动带上 `repo:status` / `repo_deployment` / `public_repo` / `repo:invite` / `security_events`，不用管。

点 **Generate token**，页面会显示一串 `ghp_xxxxxxxx...`。

**只显示一次，复制下来先存备忘录。**

---

## 二、配 MCP 服务器

GitHub 官方的远程 MCP，不用装任何东西，手机直连。

**服务器地址**

```
https://api.githubcopilot.com/mcp/
```

**类型**：Streamable HTTP（有的客户端叫 `http`、`streamable_http`、`Remote`）

**请求头**

```
Authorization: Bearer ghp_你刚才那串
```

---

## 三、在 RikkaHub 里填

设置 → **MCP 服务器**（或「扩展 / 工具 / Agent」里的 MCP 一栏）→ **添加**：

| 字段 | 值 |
|---|---|
| 名称 | `github` |
| 类型 / Transport | `Streamable HTTP` |
| URL | `https://api.githubcopilot.com/mcp/` |
| Header 名 | `Authorization` |
| Header 值 | `Bearer ghp_xxxxxxxx` |

如果有「启用」开关，打开。保存后点「测试连接」或「刷新工具列表」。

成功的话会看到一批工具，名字大概是：

```
create_or_update_file   create_repository      get_file_contents
push_files              list_commits           create_branch
list_issues             create_pull_request   search_code
actions_list            actions_get            get_job_logs
```

看到 `create_or_update_file` 和 `actions_list` 就说明通了。

### 如果客户端只认 JSON 配置

```json
{
  "mcpServers": {
    "github": {
      "type": "http",
      "url": "https://api.githubcopilot.com/mcp/",
      "headers": {
        "Authorization": "Bearer ghp_xxxxxxxx"
      }
    }
  }
}
```

### 如果只支持 SSE

老版本客户端只支持 SSE，试试把地址换成：

```
https://api.githubcopilot.com/mcp/sse
```

不行就说明客户端太老，用 JSON 配置那种形式，或者升级客户端。

---

## 四、配好之后跟我说什么

只要一句话：

> **MCP 配好了**

我就会自己动手：

1. `create_repository` 建一个 `xhsspy` 仓库（public，这样 Release 下载不需要登录）
2. 逐个 `create_or_update_file` 把源码传上去
3. `actions_list` 看构建状态，`get_job_logs` 看编译报错
4. 编好了给你 Release 链接

你在手机上点那个链接，下载 `XhsSpy.apk`，装上去，LSPatch 里选中它当模块。

---

## 五、万一 MCP 配不上

不配也能干，只是要你手动几步：

1. 手机浏览器开 `https://github.com/new`，建一个 public 仓库叫 `xhsspy`
2. 进去点 **Add file → Upload files**
3. 我这边会把整个项目打成一个 zip 给你，你上传那个 zip 到仓库根目录
4. 仓库会自动跑 Actions（`build.yml` 里已经写了解压源码的兜底逻辑）
5. 编完在仓库右侧 **Releases** 里下 APK

这条路你只需要会「上传一个文件」。

---

## 六、安全提醒

- Token 等于你账号的钥匙，别往公开的地方贴
- 用完可以在 `https://github.com/settings/tokens` 一键撤销
- 我只会用它做这四件事：建仓库、传代码、跑 Actions、发 Release
- 这个仓库是 public 的（Release 免登录下载），但里面只有 XhsSpy 模块源码，没有你的账号信息

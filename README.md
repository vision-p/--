# 小组借物登记

一个小组共用的物品借还登记系统。谁把东西借走了、什么时候该还、有没有逾期，一页看完。

- **前端**：原生 HTML / CSS / JavaScript，没有用框架
- **后端**：自己手写的 Java 服务，没有用 Spring
- **数据库**：SQLite，数据存在硬盘上的 `data.db` 文件里

## 目录结构

```
借用登记项目/
├── run.bat                双击启动（编译 + 运行）
├── lib/sqlite-jdbc.jar    SQLite 驱动
├── server/                后端
│   ├── Server.java        收请求、发网页文件
│   ├── Api.java           五个接口的具体逻辑
│   └── Db.java            建表、连数据库
├── 小组借物登记/           前端
│   ├── index.html         页面 + 全部交互逻辑
│   └── style.css          样式
└── data.db                数据库文件，第一次运行时自动生成
```

## 怎么运行

需要 JDK 21。

1. 双击 `run.bat`
2. 看到 `Server starting at http://localhost:8080` 后，用浏览器打开 <http://localhost:8080>
3. 要停止，在那个黑窗口里按 `Ctrl+C`

## 演示步骤

1. 在「物品名称」输入 `雨伞`，点「新增物品」
2. 点「借出」，借用人填 `张三`，应还日期随便填一个
3. 应还日期填**昨天**，物品会标成「已逾期」，顶部「已逾期」数字加一
4. 刷新页面，数据还在——因为存在数据库里，不是存在浏览器里
5. 点「归还」，雨伞恢复成「可借」
6. 点「删除」，需要再点「继续删除」和「确认删除」才会真的删掉；删掉之后，之前的借还记录仍然保留

## 接口

| 方法 | 路径 | 作用 | 参数 |
|---|---|---|---|
| GET | `/api/state` | 取全部物品和记录 | — |
| POST | `/api/items` | 新增物品 | `name=雨伞` |
| POST | `/api/items/{id}/borrow` | 借出 | `borrower=张三&dueDate=2026-12-01` |
| POST | `/api/items/{id}/return` | 归还 | — |
| DELETE | `/api/items/{id}` | 删除物品 | — |

参数格式是 `application/x-www-form-urlencoded`，也就是 `键=值&键=值`，中文会被转成 `%E9%9B%A8...` 这种编码。

## 一次「新增物品」的完整链路

```
网页点「新增物品」
  → 前端 fetch("POST /api/items", name=雨伞)
    → Server 收到请求，解析出 method=POST, path=/api/items, body=name=雨伞
      → Api.addItem() 把参数交给 SQL 的 INSERT
        → SQLite 往 data.db 的 items 表插一行
      ← 返回 {"ok":true}
    ← 前端再调一次 GET /api/state，拿到最新列表
  ← 重新画页面
```

## 数据表

```sql
-- 物品表：借出去就是改这一行的 borrower 和 due_date
CREATE TABLE items (
  id       INTEGER PRIMARY KEY AUTOINCREMENT,
  name     TEXT NOT NULL,
  borrower TEXT,
  due_date TEXT
);

-- 记录表：借、还、新增、删除，每发生一件事就加一行
CREATE TABLE records (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  text       TEXT NOT NULL,
  created_at TEXT NOT NULL
);
```

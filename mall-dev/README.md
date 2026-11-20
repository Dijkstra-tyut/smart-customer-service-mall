# Mall 商城与智能客服

提交到个人仓库时，只需纳入运行所需的前后端、Agent、数据库初始化脚本和图片资源。仓库根目录的 `.gitignore` 排除了本地依赖、构建产物、日志、IDE 状态和非运行文档；商品图片与 SQL 未被排除。

| 目录 | 用途 |
| --- | --- |
| `mall-server/` | Spring Boot 后端（Java 17） |
| `mall-portal/` | 用户商城（Vue 3） |
| `mall-admin/` | 管理后台（Vue 3） |
| `mall-customer/` | LangGraph 智能客服（Python） |
| `db/mall.sql`、`db/schema.sql` | 数据库初始化数据与客服表结构 |
| `img/product/` | 商品图片，由后端 `/portal/product/img` 提供 |
| `docker/local/docker-compose.yml` | 本地 MySQL、Redis、RabbitMQ、Elasticsearch |

## 本地启动

需要 Java 17、Python 3.12、Node.js/npm，以及 MySQL 8 和 Redis。可在项目根目录用 `docker compose -f docker/local/docker-compose.yml up -d` 启动中间件；首次创建 MySQL 数据卷时会导入 `db/mall.sql`。如果使用已有数据库，先自行核对或导入所需表。数据库初始化会改写数据，请勿对有真实订单的数据库直接执行。

后端在 `mall-server/` 目录执行：

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev,local'
```

用户商城和管理后台分别在 `mall-portal/`、`mall-admin/` 执行：

```powershell
npm ci
npm run dev
```

Agent 在 `mall-customer/` 目录执行。先将 `.env.example` 复制为 `.env`，配置 `OPENAI_API_KEY`、`OPENAI_BASE_URL`、`MODEL_NAME` 和 `REDIS_URL`，再运行：

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python -m api.main
```

默认地址：后端 `http://localhost:8800`，商城 `http://localhost:3001`，后台 `http://localhost:3002/admin/`，Agent `http://localhost:8000`。商城客服由 Java 后端转发到 Agent；要使用客服，需同时运行后端与 Agent。使用本机 MySQL/Redis 而不启动 RabbitMQ、Elasticsearch 时，后端使用 `dev,local` profiles。

本地开发的演示账号和密码、数据库密码请按配置自行修改。`.env` 文件不应提交到仓库。项目许可证见 `LICENSE`。

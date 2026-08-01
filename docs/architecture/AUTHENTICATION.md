# 认证架构基线

> 状态：已确认  
> 版本：0.1  
> 更新日期：2026-07-18

## 1. 架构结论

认证采用分阶段演进路线：

```text
0.1：服务端 Session + 安全 Cookie

长期：OAuth2 / OIDC + 浏览器 BFF + JWT 资源服务
```

0.1 的内部员工和管理员通过同一个入口登录，由 Spring Security 在 `app-server` 建立服务端 Session。浏览器只持有 HttpOnly Cookie，不保存 JWT、Refresh Token、密码或其他认证凭据。

未来出现移动端、开放 API 或独立服务后，再引入标准 OAuth2/OIDC 授权体系。浏览器仍通过 BFF 和安全 Cookie 维持会话；BFF、移动端或服务客户端使用标准流程获取 Token，独立资源服务验证 JWT。

这不是“永久不使用 JWT”，也不是同时实现两套认证。Session 是 0.1 唯一主路径，JWT 是出现真实多端或多服务调用方后的资源访问凭据。

## 2. 0.1 认证边界

```text
匿名访问者
    → 公开接口

内部浏览器
    → HttpOnly Session Cookie
    → app-server
    → 当前用户与权限
```

- 匿名访问者不建立内部身份，只能读取允许公开的数据；
- 内部员工和管理员使用同一账号体系与 Session；
- 管理员是拥有管理权限的内部账号，不是独立认证类型；
- 后端负责最终认证和权限判断；
- 前端只读取当前用户、登录状态和权限结果，不解析服务端认证凭据；
- 0.1 不包含外部客户登录、移动端认证、开放 API 和服务间认证。

0.1 不建设 Session 持久化、并发登录控制和应用重启后的会话恢复。登录实现时只确定一个明确的会话超时时间，不展开额外账号安全策略。

## 3. 浏览器安全要求

- Session Cookie 必须使用 `HttpOnly`；
- 生产环境 Cookie 必须使用 `Secure`；
- Cookie 必须配置明确且经过部署验证的 `SameSite` 和 Path；
- 状态变更请求必须具备 CSRF 防护；
- 登录成功后必须防止 Session 固定攻击；
- 退出登录必须销毁服务端 Session；
- 未登录返回 `401`，已登录但无权限返回 `403`；
- 禁止把 Session ID、JWT、Refresh Token 或密码写入日志；
- 禁止前端把认证凭据保存到 `localStorage` 或普通可读 Cookie。

具体 Cookie 名称、CSRF 交互方式和跨域策略在部署方式确认后定义，禁止通过全开放 CORS 或关闭 CSRF 规避配置问题。

## 4. 当前投入的长期价值

0.1 建设的以下能力在未来 OAuth2/OIDC 和多服务体系中继续使用：

- 内部账号和凭据校验；
- 稳定的权限编码与后端授权规则；
- Spring Security 中的当前主体和权限上下文；
- 登录、退出、当前用户和权限加载的产品流程；
- 密码哈希和认证失败处理；
- 前端对登录状态、当前用户、`401` 和 `403` 的统一处理；
- 登录、越权和退出测试；
- 业务模块不直接解析 Cookie、Session ID 或 JWT 的安全边界。

未来变化的是身份凭据的签发、传递和验证方式，不应该重写账号语义、权限规则、业务 Service 和前端产品流程。

## 5. 长期多端与多服务形态

```text
浏览器
    → 安全 Cookie
    → BFF
    → JWT Access Token
    → 资源服务

移动端或桌面端
    → OAuth2 Authorization Code + PKCE
    → Access Token
    → 资源服务

服务客户端
    → OAuth2 Client Credentials 或后续确认的服务认证方式
    → Access Token
    → 资源服务
```

长期方向中的职责是：

- OIDC 负责登录身份和企业统一登录；
- OAuth2 负责客户端获得受限的 API 访问权限；
- JWT 是资源服务可独立验证的一种 Access Token 格式；
- BFF 负责避免浏览器 JavaScript 直接接触 Access Token 和 Refresh Token；
- 资源服务使用 Spring Security Resource Server 验证签发者、接收方、有效期、签名和 Scope。

JWT 和不透明 Token 的最终选择、授权服务器由本项目提供还是接入外部身份平台，都必须在真实多端或服务拆分需求出现后确认。

## 6. 为未来保留边界，而不是预实现

0.1 必须做到：

- Controller 和安全配置可以使用 Spring Security 的认证上下文；
- 业务 Service 只依赖明确的当前用户与权限语义，不自行读取 HTTP Cookie 或解析 Token；
- 权限编码不能绑定 Session ID、JWT Claim 名称或某个前端路由实现；
- 对外 API DTO 不暴露认证框架内部对象；
- 登录方式变化不能要求修改无关业务表和业务 Mapper。

0.1 禁止创建：

- 自定义 JWT 工具类、签发接口和解析过滤器；
- Access Token、Refresh Token 和黑名单表；
- Session/JWT 双模式切换开关；
- 没有真实客户端的 OAuth2 客户端注册；
- 占位授权服务器、JWK 密钥和虚假服务间调用；
- 为未来协议建立没有调用方的认证接口层。

## 7. 演进触发条件

满足以下至少一个已确认需求时，才进入 OAuth2/OIDC 和 JWT 的实现设计：

- 出现原生移动端或桌面端；
- 需要向第三方开放受保护 API；
- 某个业务模块被拆成独立部署的资源服务；
- 需要接入企业统一身份平台；
- 多个独立应用需要共享登录或授权；
- 浏览器需要访问由其他资源服务提供的 API。

进入该阶段时必须单独确认授权服务器、授权流程、客户端类型、Token 格式、Scope、Audience、密钥轮换、撤销、刷新、审计和迁移方案。

## 8. 后续边界

具体认证配置只在实现登录时按当前需求确定，其他未提出能力不提前规划。长期授权服务器与身份提供商在真实多端或多服务需求出现后再选择。

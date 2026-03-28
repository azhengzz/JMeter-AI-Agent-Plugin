# JMeter AI Plugin - 远程调试指南

本文档介绍如何使用远程调试方式调试 JMeter AI 插件。

## 前提条件

- Java 17 或更高版本
- Maven 3.x
- Apache JMeter 5.6.3 或更高版本
- IDE（IntelliJ IDEA / VS Code / Eclipse）

## 步骤 1：修改 JMeter 路径

编辑 `pom.xml` 文件，找到第 252 行，将 JMeter 路径修改为你自己的安装路径：

```xml
<copy file="${project.build.directory}/${project.build.finalName}.jar"
      todir="D:/Tools/apache-jmeter-5.6.3/lib/ext"/>
```

## 步骤 2：构建项目

在项目根目录执行：

注意：执行前用`java -version`命令再次检查下java版本

```bash
mvn clean package -DskipTests
mvn install
```

或者一步完成：

```bash
mvn clean install
```

构建成功后，JAR 文件会自动复制到 JMeter 的 `lib/ext` 目录。

## 步骤 3：启动 JMeter 并开启调试端口

### Windows

编辑 `jmeter.bat` 文件，在开头添加以下内容：

```cmd
set JVM_ARGS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
```

或者直接在命令行启动：

```cmd
set JVM_ARGS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
jmeter.bat
```

### Linux/macOS

编辑 `jmeter.sh` 文件，在 JVM 启动参数中添加：

```bash
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
```

或者使用环境变量：

```bash
export JVM_ARGS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
./jmeter.sh
```

### 验证调试端口已开启

启动 JMeter 后，应该能在控制台看到类似输出：

```
Listening for transport dt_socket at address: 5005
```

## 步骤 4：在 IDE 中连接调试器

### IntelliJ IDEA

#### 方法 A：使用 Attach to Process

1. 点击菜单 **Run** → **Attach to Process**
2. 在列表中选择 `JMeter` 进程
3. 开始调试

#### 方法 B：使用 Remote JVM Debug 配置（推荐）

1. 点击 **Run** → **Edit Configurations...**
2. 点击左上角 **+** → **Remote JVM Debug**
3. 配置如下：
   - **Name**: `JMeter Debug`
   - **Host**: `localhost`
   - **Port**: `5005`
4. 点击 **OK**
5. 点击 **Debug** 按钮连接

### VS Code

创建或编辑 `.vscode/launch.json`：

```json
{
    "version": "0.2.0",
    "configurations": [
        {
            "type": "java",
            "name": "Attach to JMeter",
            "request": "attach",
            "hostName": "localhost",
            "port": 5005
        }
    ]
}
```

然后按 `F5` 或点击 **Run and Debug**。

### Eclipse

1. 点击菜单 **Run** → **Debug Configurations...**
2. 在左侧选择 **Remote Java Application**
3. 点击左上角 **New launch configuration** 图标
4. 配置如下：
   - **Project**: `jmeter-agent`
   - **Host**: `localhost`
   - **Port**: `5005`
5. 点击 **Debug**

## 步骤 5：设置断点并调试

### 推荐设置断点的位置

| 文件 | 行号 | 说明 |
|------|------|------|
| `ClaudeService.java` | ~120 | 客户端初始化 |
| `ClaudeService.java` | ~199 | `generateResponse` 方法入口 |
| `OpenAiService.java` | ~112 | 客户端初始化 |
| `AiChatPanel.java` | ~557 | `sendMessage` 方法 |
| `MessageProcessor.java` | ~34 | `processMarkdownMessage` 方法 |

### 调试流程

1. 在 IDE 中设置断点
2. 确保 JMeter 已启动并开启调试端口
3. 在 IDE 中连接到 JMeter
4. 在 JMeter 中操作插件：右键 **Test Plan** → **Add** → **Non-Test Elements** → **Feather Wand**
5. 程序会在断点处暂停，可以查看变量、单步执行等

## 调试技巧

### 查看日志

JMeter 的日志文件位于：`<JMETER_HOME>/bin/jmeter.log`

### 热重载（Hot Swap）

如果使用 IntelliJ IDEA，可以：

1. 修改代码后，点击 **Build** → **Recompile 'xxx.java'**
2. 无需重启 JMeter，更改会自动生效

### 条件断点

在断点上右键 → **Edit Breakpoint** → 设置条件，例如：

```
message.contains("@this")
```

### 表达式求值

在调试时，可以：

- **IntelliJ IDEA**: 按 `Alt+F8` 或点击 **Evaluate Expression**
- **Eclipse**: 按 `Ctrl+Shift+I` 或使用 **Display** 视图

## 常见问题

### Q: 连接失败 "Connection refused"

**A**: 确保 JMeter 已启动并开启了调试端口，检查端口 5005 是否被占用：

```bash
# Windows
netstat -ano | findstr :5005

# Linux/macOS
lsof -i :5005
```

### Q: 断点不起作用

**A**: 确保源代码与编译的 class 文件一致，重新构建项目：

```bash
mvn clean install
```

### Q: 无法查看变量值

**A**: 确保编译时包含调试信息：

```bash
mvn clean install -Dmaven.compiler.debug=true
```

## 快速验证

启动 JMeter 后，验证插件是否加载成功：

1. 右键 **Test Plan** → **Add** → **Non-Test Elements**
2. 查看是否有 **Feather Wand** 选项
3. 如果有，说明插件已成功加载

## 项目配置参考

### jmeter.properties 配置示例

```properties
# Anthropic API
anthropic.api.key=your_api_key_here
claude.default.model=claude-3-5-sonnet-20241022

# OpenAI API (可选)
openai.api.key=your_openai_api_key
openai.default.model=gpt-4o

# 调试日志
anthropic.log.level=debug
openai.log.level=DEBUG
```

### IDEA 运行配置示例

如果需要在 IDEA 中直接运行 JMeter：

- **Main class**: `org.apache.jmeter.NewDriver`
- **VM options**:
  ```
  -Djava.class.path=D:/Tools/apache-jmeter-5.6.3/lib/ext/jmeter-agent-2.0.2.jar
  -Dsearch_paths=D:/Tools/apache-jmeter-5.6.3/lib/ext
  -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
  ```
- **Working directory**: `D:/Tools/apache-jmeter-5.6.3/bin`
- **Use classpath of module**: `jmeter-agent.main`

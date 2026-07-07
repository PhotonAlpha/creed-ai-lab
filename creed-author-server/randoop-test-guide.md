# Randoop 自动生成单元测试 — 执行步骤

以 `com.creed.auth.json.JacksonUtils` 为例，记录用 [Randoop](https://randoop.github.io/randoop/) 为本模块的类自动生成回归测试并纳入 `src/test` 的完整流程。产出示例见 `src/test/java/com/creed/auth/json/JacksonUtilsTest.java`（48 个用例，`mvn test` 全部通过）。

## 前置条件

- 已下载 Randoop，并 `export` 环境变量（注意必须 `export`，否则脚本/子 shell 里取不到，会报 `ClassNotFoundException: randoop.main.Main`）：

  ```bash
  export RANDOOP_JAR=/Users/ethan/Desktop/workspace/tools/randoop/randoop-4.3.4/randoop-all-4.3.4.jar
  ```

- 被测类已编译到 `target/classes`（改过代码要先 `mvn compile`）。

## 步骤 1：生成依赖 classpath

Randoop 通过反射加载被测类，classpath 必须包含 **被测类 + 它的全部依赖 + randoop jar** 三部分，缺一就会 `ClassNotFoundException`。

```bash
cd creed-author-server
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
```

## 步骤 2：运行 Randoop 生成测试

```bash
java -classpath "target/classes:$(cat cp.txt):$RANDOOP_JAR" \
  randoop.main.Main gentests \
  --testclass=com.creed.auth.json.JacksonUtils \
  "--omit-methods=com\.creed\.auth\.json\.JacksonUtils\.init" \
  --time-limit=60 --output-limit=60 \
  --junit-output-dir=randoop-tests
```

参数说明：

| 参数 | 说明 |
| --- | --- |
| `--testclass` | 被测类的全限定名，可重复指定多个 |
| `--omit-methods` | 正则，排除不该被随机调用的方法。`JacksonUtils.init` 会替换全局 `ObjectMapper`，随机调用会让后续测试结果依赖执行顺序，必须排除 |
| `--time-limit` | 生成时间上限（秒） |
| `--output-limit` | 最多输出的用例数 |
| `--junit-output-dir` | 生成文件的输出目录 |

产出：`randoop-tests/RegressionTest0.java`（具体用例）和 `RegressionTest.java`（JUnit 4 套件入口，纳入项目时不需要）。

查看帮助用 `help gentests` 子命令（**不是** `--help`）：

```bash
java -classpath "$RANDOOP_JAR" randoop.main.Main help gentests
```

## 步骤 3：JUnit 4 → JUnit 5 转换后放入 src/test

Randoop 生成的是 JUnit 4 代码，本项目测试依赖只有 JUnit 5（surefire 走 junit-platform），直接放入 `src/test` 无法编译。转换要点：

1. 文件头加 `package`（与被测类同包，如 `com.creed.auth.json`），类重命名为 `XxxTest`（如 `JacksonUtilsTest`），放到 `src/test/java` 对应包路径下。
2. `import org.junit.Test;` → `import org.junit.jupiter.api.Test;`；删除 `@FixMethodOrder` 及 `org.junit.FixMethodOrder`、`org.junit.runners.MethodSorters` 两个 import（用例相互独立，不需要固定顺序）。
3. 断言不要逐行改参数顺序（JUnit 4 是 `assertEquals(msg, expected, actual)`，JUnit 5 的 message 在最后，批量调整容易出错）。做法是：全局把 `org.junit.Assert.` 删掉，然后在类底部加一组与 JUnit 4 签名一致的私有适配方法，内部委托给 `Assertions`：

   ```java
   private static void assertEquals(String message, Object expected, Object actual) {
       org.junit.jupiter.api.Assertions.assertEquals(expected, actual, message);
   }

   private static void assertTrue(String message, boolean condition) {
       org.junit.jupiter.api.Assertions.assertTrue(condition, message);
   }

   private static void assertNotNull(Object actual) {
       org.junit.jupiter.api.Assertions.assertNotNull(actual);
   }

   private static void assertArrayEquals(byte[] expected, byte[] actual) {
       org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
   }

   private static void fail(String message) {
       org.junit.jupiter.api.Assertions.fail(message);
   }
   ```

   （如果生成的用例里出现了其他类型的 `assertArrayEquals` 重载，按同样方式补充即可。）

## 步骤 4：验证

```bash
mvn test -Dtest=JacksonUtilsTest -DfailIfNoTests=true
```

预期输出：

```
Tests run: 48, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

运行时控制台出现的 `JsonParseException` 堆栈是 `JacksonUtils` 对非法 JSON 输入 `log.error` 打印的日志，属于用例覆盖的预期异常路径，不是测试失败。

## 注意事项

- 这批用例是**回归快照**性质：锁定当前行为（如 `toJsonString(false)` 返回 `"false"`、非法字节数组抛 `RuntimeException`）。以后修改 `ObjectMapper` 配置导致行为变化时它们会报警，届时按需重新生成或修正断言。
- 有全局可变状态的方法（setter、`init` 这类）一律用 `--omit-methods` 排除，否则生成的用例不可复现。
- 生成过程的中间产物（`cp.txt`、`randoop-tests/`）不要提交，转换后的 `XxxTest.java` 才是最终产物。

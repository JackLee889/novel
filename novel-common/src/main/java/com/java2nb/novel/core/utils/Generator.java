package com.java2nb.novel.core.utils;

import lombok.SneakyThrows;
import org.mybatis.generator.api.MyBatisGenerator;
import org.mybatis.generator.config.Configuration;
import org.mybatis.generator.config.xml.ConfigurationParser;
import org.mybatis.generator.internal.DefaultShellCallback;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 代码生成器
 *
 * @author 11797
 */
public class Generator {

    @SneakyThrows
    public static void main(String[] args) {
        //MBG 执行过程中的警告信息
        //读取我们的 MBG 配置文件
        InputStream is = Generator.class.getResourceAsStream("/mybatis/generatorConfig.xml");
        ConfigurationParser cp = new ConfigurationParser();
        Configuration config = cp.parseConfiguration(is);
        is.close();
        //当生成的代码重复时，不要覆盖原代码
        DefaultShellCallback callback = new DefaultShellCallback();
        //创建 MBG
        MyBatisGenerator myBatisGenerator = new MyBatisGenerator.Builder()
                .withConfiguration(config).withShellCallback(callback).withOverwriteEnabled(false).build();
        //执行生成代码
        List<String> warnings = myBatisGenerator.generateAndWrite();
        warnings.addAll(cp.getWarnings());
        //输出警告信息
        for (String warning : warnings) {
            System.out.println(warning);
        }
    }
}

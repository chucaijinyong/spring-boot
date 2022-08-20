/*
 * Copyright 2012-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.loader;

import java.lang.reflect.Method;

/**
 * Utility class that is used by {@link Launcher}s to call a main method. The class
 * containing the main method is loaded using the thread context class loader.
 *
 * @author Phillip Webb
 * @author Andy Wilkinson
 */
public class MainMethodRunner {

	private final String mainClassName;

	private final String[] args;

	/**
	 * Create a new {@link MainMethodRunner} instance.
	 * @param mainClass the main class
	 * @param args incoming arguments
	 */
	public MainMethodRunner(String mainClass, String[] args) {
		this.mainClassName = mainClass;
		this.args = (args != null) ? args.clone() : null;
	}

	/**
	* 运行主程序
	*/
	public void run() throws Exception {
		// 从SpringBoot自定义的类加载器中获取运行的主类class
		Class<?> mainClass = Thread.currentThread().getContextClassLoader()
				.loadClass(this.mainClassName);
		Method mainMethod = mainClass.getDeclaredMethod("main", String[].class);
		// 反射执行主配置类的main方法就把项目启动起来了【为什么对象传了null，因为静态方法不归属于所在的类，而是属于当前所在的类从属的Class对象】
		mainMethod.invoke(null, new Object[] { this.args });
	}

}

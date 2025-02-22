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

package org.springframework.boot.autoconfigure;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * {@link DeferredImportSelector} to handle {@link EnableAutoConfiguration
 * auto-configuration}. This class can also be subclassed if a custom variant of
 * {@link EnableAutoConfiguration @EnableAutoConfiguration} is needed.
 *
 * @author Phillip Webb
 * @author Andy Wilkinson
 * @author Stephane Nicoll
 * @author Madhura Bhave
 * @since 1.3.0
 * @see EnableAutoConfiguration
 *
 * DeferredImportSelector是spring提供的，Deferred延迟之意，ImportSelector 的一种扩展，在处理完所有 @Configuration 类型的Bean之后运行。
 * 当所选导入为 @Conditional 时，这种类型的选择器特别有用。
 */
public class AutoConfigurationImportSelector
		implements DeferredImportSelector, BeanClassLoaderAware, ResourceLoaderAware,
		BeanFactoryAware, EnvironmentAware, Ordered {

	private static final AutoConfigurationEntry EMPTY_ENTRY = new AutoConfigurationEntry();

	private static final String[] NO_IMPORTS = {};

	private static final Log logger = LogFactory
			.getLog(AutoConfigurationImportSelector.class);

	private static final String PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE = "spring.autoconfigure.exclude";

	private ConfigurableListableBeanFactory beanFactory;

	private Environment environment;

	private ClassLoader beanClassLoader;

	private ResourceLoader resourceLoader;

	/**
	* 返回的就是自动配置类的字符串数组，这些数组的元素都是全限定名，到时候会通过反射方式创建
	 * 不会进到这里，当实现了getImportGroup之后，会走group里面的process方法
	*/
	@Override
	public String[] selectImports(AnnotationMetadata annotationMetadata) {
//		会在所有@Configuration都解析完之后才执行
		if (!isEnabled(annotationMetadata)) {
			return NO_IMPORTS;
		}
		// 加载自动配置类
		AutoConfigurationMetadata autoConfigurationMetadata = AutoConfigurationMetadataLoader.loadMetadata(this.beanClassLoader);
		AutoConfigurationEntry autoConfigurationEntry = getAutoConfigurationEntry(autoConfigurationMetadata, annotationMetadata);
		return StringUtils.toStringArray(autoConfigurationEntry.getConfigurations());
	}

	/**
     * 获得 AutoConfigurationEntry 对象
     *
	 * Return the {@link AutoConfigurationEntry} based on the {@link AnnotationMetadata}
	 * of the importing {@link Configuration @Configuration} class.
	 * @param autoConfigurationMetadata the auto-configuration metadata
	 * @param annotationMetadata the annotation metadata of the configuration class
	 * @return the auto-configurations that should be imported
	 * 获取自动配置类
	 */
	protected AutoConfigurationEntry getAutoConfigurationEntry(AutoConfigurationMetadata autoConfigurationMetadata, AnnotationMetadata annotationMetadata) {
	    // 1. 判断是否开启。如未开启，返回空串
		if (!isEnabled(annotationMetadata)) {
			return EMPTY_ENTRY;
		}
		// 2. 获得@EnableAutoConfiguration注解的属性
		AnnotationAttributes attributes = getAttributes(annotationMetadata);
		// 3. 获得spring.factories中所有的AutoConfiguration 符合条件的配置类的数组
		List<String> configurations = getCandidateConfigurations(annotationMetadata, attributes);
		// 3.1 移除重复的配置类，按照类名去重
		configurations = removeDuplicates(configurations);
		// 4. 获得需要排除的配置类，通过@EnableAutoConfiguration注解的exclude属性，或者spring.autoconfigure.exclude属性
		Set<String> exclusions = getExclusions(annotationMetadata, attributes);
		// 4.1 校验排除的配置类是否合法
		checkExcludedClasses(configurations, exclusions);
		// 4.2 从 configurations 中，移除需要排除的配置类
		configurations.removeAll(exclusions);
		// 5. 根据条件（Condition），过滤掉不符合条件的配置类
		// 获取spring.factories中的AutoConfigurationImportFilter对AutoConfiguration进行过滤
		// 默认会拿到OnBeanCondition、OnClassCondition、OnWebApplicationCondition
		// 这三个会去判断上面的AutoConfiguration是否符合它们自身所要求的条件，不符合的会过滤掉，表示不会进行解析了
		// 会利用spring-autoconfigure-metadata.properties中的配置来进行过滤
		// spring-autoconfigure-metadata.properties文件中的内容是利用Java中的AbstractProcessor技术在编译时生成出来的
		configurations = filter(configurations, autoConfigurationMetadata);
		// 6. 触发自动配置类引入完成的事件
		// configurations表示合格的，exclusions表示被排除的，把它们记录在ConditionEvaluationReportAutoConfigurationImportListener中
		fireAutoConfigurationImportEvents(configurations, exclusions);
		// 7. 创建 AutoConfigurationEntry 对象
		// 最后返回的AutoConfiguration都是符合条件的
		return new AutoConfigurationEntry(configurations, exclusions);
	}

	/**
	* 会走这里
	*/
	@Override
	public Class<? extends Group> getImportGroup() {
		return AutoConfigurationGroup.class;
	}

	protected boolean isEnabled(AnnotationMetadata metadata) {
	    // 判断 "spring.boot.enableautoconfiguration" 配置判断，是否开启自动配置。
        // 默认情况下（未配置），开启自动配置。
		if (getClass() == AutoConfigurationImportSelector.class) {
			return getEnvironment().getProperty(EnableAutoConfiguration.ENABLED_OVERRIDE_PROPERTY, Boolean.class, true);
		}
		return true;
	}

	/**
	 * Return the appropriate {@link AnnotationAttributes} from the
	 * {@link AnnotationMetadata}. By default this method will return attributes for
	 * {@link #getAnnotationClass()}.
	 * @param metadata the annotation metadata
	 * @return annotation attributes
	 */
	protected AnnotationAttributes getAttributes(AnnotationMetadata metadata) {
		String name = getAnnotationClass().getName();
		// 获得注解的属性
		AnnotationAttributes attributes = AnnotationAttributes.fromMap(metadata.getAnnotationAttributes(name, true));
		// 断言
		Assert.notNull(attributes,
				() -> "No auto-configuration attributes found. Is "
						+ metadata.getClassName() + " annotated with "
						+ ClassUtils.getShortName(name) + "?");
		return attributes;
	}

	/**
	 * Return the source annotation class used by the selector.
	 * @return the annotation class
	 */
	protected Class<?> getAnnotationClass() {
		return EnableAutoConfiguration.class;
	}

	/**
	 * Return the auto-configuration class names that should be considered. By default
	 * this method will load candidates using {@link SpringFactoriesLoader} with
	 * {@link #getSpringFactoriesLoaderFactoryClass()}.
	 * @param metadata the source metadata
	 * @param attributes the {@link #getAttributes(AnnotationMetadata) annotation
	 * attributes}
	 * @return a list of candidate configurations
	 * META-INF/spring.factories里面的文件是key，value的形式，是Map<String, List<String>>结构，最后会获取Key中包含EnableAutoConfig对应的list集合
	 */
	protected List<String> getCandidateConfigurations(AnnotationMetadata metadata, AnnotationAttributes attributes) {
	    // 加载指定类型 EnableAutoConfiguration 对应的，在 `META-INF/spring.factories` 里的类名的数组     getSpringFactoriesLoaderFactoryClass()传入的Class就是 @EnableAutoConfiguration
		List<String> configurations = SpringFactoriesLoader.loadFactoryNames(getSpringFactoriesLoaderFactoryClass(), getBeanClassLoader());
		// 断言，非空
		Assert.notEmpty(configurations, "No auto configuration classes found in META-INF/spring.factories. If you " + "are using a custom packaging, make sure that file is correct.");
		return configurations;
	}

	/**
	 * Return the class used by {@link SpringFactoriesLoader} to load configuration
	 * candidates.
	 * @return the factory class
	 */
	protected Class<?> getSpringFactoriesLoaderFactoryClass() {
		return EnableAutoConfiguration.class;
	}

	private void checkExcludedClasses(List<String> configurations, Set<String> exclusions) {
		// 获得 exclusions 不在 invalidExcludes 的集合，添加到 invalidExcludes 中
	    List<String> invalidExcludes = new ArrayList<>(exclusions.size());
		for (String exclusion : exclusions) {
			if (ClassUtils.isPresent(exclusion, getClass().getClassLoader()) // classpath 存在该类
					&& !configurations.contains(exclusion)) { // configurations 不存在该类
				invalidExcludes.add(exclusion);
			}
		}
		// 如果 invalidExcludes 非空，抛出 IllegalStateException 异常
		if (!invalidExcludes.isEmpty()) {
			handleInvalidExcludes(invalidExcludes);
		}
	}

	/**
	 * Handle any invalid excludes that have been specified.
	 * @param invalidExcludes the list of invalid excludes (will always have at least one
	 * element)
	 */
	protected void handleInvalidExcludes(List<String> invalidExcludes) {
		StringBuilder message = new StringBuilder();
		for (String exclude : invalidExcludes) {
			message.append("\t- ").append(exclude).append(String.format("%n"));
		}
		throw new IllegalStateException(String
				.format("The following classes could not be excluded because they are"
						+ " not auto-configuration classes:%n%s", message));
	}

	/**
	 * Return any exclusions that limit the candidate configurations.
	 * @param metadata the source metadata
	 * @param attributes the {@link #getAttributes(AnnotationMetadata) annotation
	 * attributes}
	 * @return exclusions or an empty set
	 */
	protected Set<String> getExclusions(AnnotationMetadata metadata, AnnotationAttributes attributes) {
		Set<String> excluded = new LinkedHashSet<>();
		// 注解上的 exclude 属性
		excluded.addAll(asList(attributes, "exclude"));
		// 注解上的 excludeName 属性
		excluded.addAll(Arrays.asList(attributes.getStringArray("excludeName")));
		// 配置文件的 spring.autoconfigure.exclude 属性
		excluded.addAll(getExcludeAutoConfigurationsProperty());
		return excluded;
	}

	private List<String> getExcludeAutoConfigurationsProperty() {
	    // 一般来说，会走这块的逻辑
		if (getEnvironment() instanceof ConfigurableEnvironment) {
			Binder binder = Binder.get(getEnvironment());
			return binder.bind(PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE, String[].class).map(Arrays::asList).orElse(Collections.emptyList());
		}
		String[] excludes = getEnvironment().getProperty(PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE, String[].class);
		return (excludes != null) ? Arrays.asList(excludes) : Collections.emptyList();
	}

	private List<String> filter(List<String> configurations, AutoConfigurationMetadata autoConfigurationMetadata) {
//	    // 这里是艿艿乱加的。
//	    if (true) {
//	        return configurations;
//        }
        // 声明需要用到的变量
		long startTime = System.nanoTime(); // 记录开始时间，用于下面统计消耗的时间
		String[] candidates = StringUtils.toStringArray(configurations); // 配置类的数组
		boolean[] skip = new boolean[candidates.length]; // 每个配置类是否需要忽略的数组，通过下标互相索引
		boolean skipped = false; // 是否有需要忽略的
        // 遍历 AutoConfigurationImportFilter 数组，逐个匹配
		for (AutoConfigurationImportFilter filter : getAutoConfigurationImportFilters()) {
			// 设置 AutoConfigurationImportFilter 的属性们
		    invokeAwareMethods(filter);
		    // 执行批量匹配，并返回匹配结果
			boolean[] match = filter.match(candidates, autoConfigurationMetadata);
			// 遍历匹配结果，判断哪些需要忽略
			for (int i = 0; i < match.length; i++) {
				if (!match[i]) { // 如果有不匹配的
					skip[i] = true;
					candidates[i] = null; // 标记为空，循环的下一次，就无需匹配它了。
					skipped = true; // 标记存在不匹配的
				}
			}
		}
		// 如果没有需要忽略的，直接返回 configurations 即可
		if (!skipped) {
			return configurations;
		}
		// 如果存在需要忽略的，构建新的数组，排除掉忽略的
		List<String> result = new ArrayList<>(candidates.length);
		for (int i = 0; i < candidates.length; i++) {
			if (!skip[i]) {
				result.add(candidates[i]);
			}
		}
		// 打印，消耗的时间，已经排除的数量
		if (logger.isTraceEnabled()) {
			int numberFiltered = configurations.size() - result.size();
			logger.trace("Filtered " + numberFiltered + " auto configuration class in "
					+ TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)
					+ " ms");
		}
		// 返回
		return new ArrayList<>(result);
	}

	protected List<AutoConfigurationImportFilter> getAutoConfigurationImportFilters() {
		return SpringFactoriesLoader.loadFactories(AutoConfigurationImportFilter.class, this.beanClassLoader);
	}

	protected final <T> List<T> removeDuplicates(List<T> list) {
		return new ArrayList<>(new LinkedHashSet<>(list));
	}

	protected final List<String> asList(AnnotationAttributes attributes, String name) {
		String[] value = attributes.getStringArray(name);
		return Arrays.asList(value);
	}

	private void fireAutoConfigurationImportEvents(List<String> configurations, Set<String> exclusions) {
	    // 加载指定类型 AutoConfigurationImportListener 对应的，在 `META-INF/spring.factories` 里的类名的数组。
		List<AutoConfigurationImportListener> listeners = getAutoConfigurationImportListeners();
		if (!listeners.isEmpty()) {
		    // 创建 AutoConfigurationImportEvent 事件
			AutoConfigurationImportEvent event = new AutoConfigurationImportEvent(this, configurations, exclusions);
			// 遍历 AutoConfigurationImportListener 监听器们，逐个通知
			for (AutoConfigurationImportListener listener : listeners) {
			    // 设置 AutoConfigurationImportListener 的属性
				invokeAwareMethods(listener);
				// 通知
				listener.onAutoConfigurationImportEvent(event);
			}
		}
	}

	protected List<AutoConfigurationImportListener> getAutoConfigurationImportListeners() {
		return SpringFactoriesLoader.loadFactories(AutoConfigurationImportListener.class, this.beanClassLoader);
	}

	private void invokeAwareMethods(Object instance) {
	    // 各种 Aware 属性的注入
		if (instance instanceof Aware) {
			if (instance instanceof BeanClassLoaderAware) {
				((BeanClassLoaderAware) instance).setBeanClassLoader(this.beanClassLoader);
			}
			if (instance instanceof BeanFactoryAware) {
				((BeanFactoryAware) instance).setBeanFactory(this.beanFactory);
			}
			if (instance instanceof EnvironmentAware) {
				((EnvironmentAware) instance).setEnvironment(this.environment);
			}
			if (instance instanceof ResourceLoaderAware) {
				((ResourceLoaderAware) instance).setResourceLoader(this.resourceLoader);
			}
		}
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		Assert.isInstanceOf(ConfigurableListableBeanFactory.class, beanFactory);
		this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
	}

	protected final ConfigurableListableBeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.beanClassLoader = classLoader;
	}

	protected ClassLoader getBeanClassLoader() {
		return this.beanClassLoader;
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	protected final Environment getEnvironment() {
		return this.environment;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	protected final ResourceLoader getResourceLoader() {
		return this.resourceLoader;
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE - 1;
	}

	private static class AutoConfigurationGroup implements DeferredImportSelector.Group,
			BeanClassLoaderAware, BeanFactoryAware, ResourceLoaderAware {

        /**
         * AnnotationMetadata 的映射
         *
         * KEY：配置类的全类名
         */
		private final Map<String, AnnotationMetadata> entries = new LinkedHashMap<>();
        /**
         * AutoConfigurationEntry 的数组
         */
		private final List<AutoConfigurationEntry> autoConfigurationEntries = new ArrayList<>();

		private ClassLoader beanClassLoader;
		private BeanFactory beanFactory;
		private ResourceLoader resourceLoader;

        /**
         * 自动配置的元数据
         */
		private AutoConfigurationMetadata autoConfigurationMetadata;

		@Override
		public void setBeanClassLoader(ClassLoader classLoader) {
			this.beanClassLoader = classLoader;
		}

		@Override
		public void setBeanFactory(BeanFactory beanFactory) {
			this.beanFactory = beanFactory;
		}

		@Override
		public void setResourceLoader(ResourceLoader resourceLoader) {
			this.resourceLoader = resourceLoader;
		}

		/**
		* 最终会走这里
		*/
		@Override
		public void process(AnnotationMetadata annotationMetadata, DeferredImportSelector deferredImportSelector) {
			// 断言
		    Assert.state(
					deferredImportSelector instanceof AutoConfigurationImportSelector,
					() -> String.format("Only %s implementations are supported, got %s",
							AutoConfigurationImportSelector.class.getSimpleName(),
							deferredImportSelector.getClass().getName()));
		    // 获得所有的自动配置类，并赋值给AutoConfigurationEntries和entries属性，封装到AutoConfigurationEntry对象
			AutoConfigurationEntry autoConfigurationEntry = ((AutoConfigurationImportSelector) deferredImportSelector)
					.getAutoConfigurationEntry(getAutoConfigurationMetadata(), annotationMetadata);
			// 添加到 autoConfigurationEntries 中
			this.autoConfigurationEntries.add(autoConfigurationEntry);
			// 添加到 entries 中
//			每个自动配置类对应annotationMetadata，selectImports()方法会取出来用
			for (String importClassName : autoConfigurationEntry.getConfigurations()) {
				this.entries.putIfAbsent(importClassName, annotationMetadata);
			}
		}

		@Override
		public Iterable<Entry> selectImports() {
		    // 如果为空，则返回空数组
			if (this.autoConfigurationEntries.isEmpty()) {
				return Collections.emptyList();
			}
			// 获得 allExclusions
			Set<String> allExclusions = this.autoConfigurationEntries.stream()
					.map(AutoConfigurationEntry::getExclusions)
					.flatMap(Collection::stream).collect(Collectors.toSet());
			// 获得 processedConfigurations
			Set<String> processedConfigurations = this.autoConfigurationEntries.stream()
					.map(AutoConfigurationEntry::getConfigurations)
					.flatMap(Collection::stream)
					.collect(Collectors.toCollection(LinkedHashSet::new));
			// 从 processedConfigurations 中，移除排除的
			processedConfigurations.removeAll(allExclusions);
			// 给自动配置类排序【比如@AutoConfigureOrder，@AutoConfigureAfter，@AutoConfigureBefore】，返回结果
			return sortAutoConfigurations(processedConfigurations, getAutoConfigurationMetadata()) // 排序
                        .stream()
                        .map((importClassName) -> new Entry(this.entries.get(importClassName), importClassName)) // 创建 Entry 对象
                        .collect(Collectors.toList()); // 转换成 List
		}

        /**
         * @return 获得 AutoConfigurationMetadata 对象
         */
		private AutoConfigurationMetadata getAutoConfigurationMetadata() {
		    // 不存在，则进行加载
			if (this.autoConfigurationMetadata == null) {
				this.autoConfigurationMetadata = AutoConfigurationMetadataLoader.loadMetadata(this.beanClassLoader);
			}
			// 存在，则直接返回
			return this.autoConfigurationMetadata;
		}

		private List<String> sortAutoConfigurations(Set<String> configurations, AutoConfigurationMetadata autoConfigurationMetadata) {
			return new AutoConfigurationSorter(getMetadataReaderFactory(), autoConfigurationMetadata).getInPriorityOrder(configurations);
		}

		private MetadataReaderFactory getMetadataReaderFactory() {
			try {
				return this.beanFactory.getBean(SharedMetadataReaderFactoryContextInitializer.BEAN_NAME, MetadataReaderFactory.class);
			} catch (NoSuchBeanDefinitionException ex) {
				return new CachingMetadataReaderFactory(this.resourceLoader);
			}
		}

	}

	protected static class AutoConfigurationEntry {

        /**
         * 配置类的全类名的数组
         */
		private final List<String> configurations;
        /**
         * 排除的配置类的全类名的数组
         */
		private final Set<String> exclusions;

		private AutoConfigurationEntry() {
			this.configurations = Collections.emptyList();
			this.exclusions = Collections.emptySet();
		}

		/**
		 * Create an entry with the configurations that were contributed and their
		 * exclusions.
		 * @param configurations the configurations that should be imported
		 * @param exclusions the exclusions that were applied to the original list
		 */
		AutoConfigurationEntry(Collection<String> configurations,
				Collection<String> exclusions) {
			this.configurations = new ArrayList<>(configurations);
			this.exclusions = new HashSet<>(exclusions);
		}

		public List<String> getConfigurations() {
			return this.configurations;
		}

		public Set<String> getExclusions() {
			return this.exclusions;
		}

	}

}

package org.springframework.cloud.openfeign;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.cloud.openfeign.util.CommonUtils;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * @author xiaojiang.lxj at 2022-06-14 17:51.
 */
public class RpcReferencesRegistrar
		implements ImportBeanDefinitionRegistrar, EnvironmentAware, ResourceLoaderAware {

	private Environment environment;

	private ResourceLoader resourceLoader;

	private static final Logger log = LoggerFactory
			.getLogger(RpcReferencesRegistrar.class);

	@Override
	public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata,
			BeanDefinitionRegistry registry) {
		registerRpcReferenceClients(importingClassMetadata, registry);
	}

	public void registerRpcReferenceClients(AnnotationMetadata metadata,
			BeanDefinitionRegistry registry) {
		ClassPathScanningCandidateComponentProvider scanner = getScanner();
		scanner.setResourceLoader(this.resourceLoader);
		AnnotationTypeFilter annotationTypeFilter = new AnnotationTypeFilter(
				Component.class);
		scanner.addIncludeFilter(annotationTypeFilter);
		Set<String> basePackages = getBasePackages(metadata);
		for (String basePackage : basePackages) {
			Set<BeanDefinition> candidateComponents = scanner
					.findCandidateComponents(basePackage);
			for (BeanDefinition candidateComponent : candidateComponents) {
				if (candidateComponent instanceof AnnotatedBeanDefinition) {
					// verify annotated class is an interface
					AnnotatedBeanDefinition beanDefinition = (AnnotatedBeanDefinition) candidateComponent;
					try {
						if (beanDefinition.getBeanClassName() == null) {
							continue;
						}
						Class<?> clazz = ClassUtils.forName(
								beanDefinition.getBeanClassName(),
								ClassUtils.getDefaultClassLoader());
						Field[] fields = clazz.getDeclaredFields();
						for (Field field : fields) {
							try {
								if (!field.isAccessible()) {
									field.setAccessible(true);
								}
								RpcReference reference = field
										.getAnnotation(RpcReference.class);
								if (reference != null) {
									// register feign client bean
									registerFeignClient(registry, field.getType(),
											reference);
								}
							}
							catch (Throwable t) {
								String errorMsg = "register feign client failed for field "
										+ field.getName() + " in bean class "
										+ beanDefinition.getBeanClassName();
								log.error(errorMsg, t);
							}
						}
					}
					catch (ClassNotFoundException cnf) {
						log.error("can not resolve class = "
								+ beanDefinition.getBeanClassName(), cnf);
					}
				}
			}
		}
	}

	private void registerFeignClient(BeanDefinitionRegistry registry, Class<?> fieldType,
			RpcReference rpcReference) {
		BeanDefinitionBuilder definition = BeanDefinitionBuilder
				.genericBeanDefinition(FeignClientFactoryBean.class);
		String feignClientName;
		if (!Objects.equals(rpcReference.name(), "")) {
			feignClientName = rpcReference.name();
		}
		else if (!Objects.equals(rpcReference.value(), "")) {
			feignClientName = rpcReference.value();
		}
		else {
			// fetch from MANIFEST.MF
			feignClientName = CommonUtils.getAppNameFromJarFile(fieldType);
		}
		Assert.hasText(feignClientName,
				"can not resolve feign client name from either the name/value property of "
						+ RpcReference.class
						+ " or the App-Name entry of the rpc JarFile MANIFEST.MF");
		definition.addPropertyValue("name", resolve(feignClientName));
		definition.addPropertyValue("url", resolve(rpcReference.url()));
		definition.addPropertyValue("path", resolve(rpcReference.path()));
		definition.addPropertyValue("contextId", fieldType.getCanonicalName());
		definition.addPropertyValue("type", fieldType);
		definition.addPropertyValue("decode404", rpcReference.decode404());
		definition.addPropertyValue("fallback", rpcReference.fallback());
		definition.addPropertyValue("fallbackFactory", rpcReference.fallbackFactory());
		definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);

		String alias = fieldType.getCanonicalName() + "FeignClient";
		AbstractBeanDefinition beanDefinition = definition.getBeanDefinition();
		beanDefinition.setPrimary(rpcReference.primary());
		if (StringUtils.hasText(rpcReference.qualifier())) {
			alias = rpcReference.qualifier();
		}

		BeanDefinitionHolder holder = new BeanDefinitionHolder(beanDefinition,
				fieldType.getCanonicalName(), new String[] { alias });
		BeanDefinitionReaderUtils.registerBeanDefinition(holder, registry);
	}

	private Set<String> getBasePackages(AnnotationMetadata annotationMetadata) {
		Set<String> scanPackages = new HashSet<>();
		Map<String, Object> attrs = annotationMetadata
				.getAnnotationAttributes(EnableRpcReferences.class.getName());
		if (attrs == null) {
			throw new IllegalStateException("get null attribute of "
					+ EnableRpcReferences.class.getName() + " : " + annotationMetadata);
		}
		String[] value = (String[]) attrs.get("value");
		String[] basePackages = (String[]) attrs.get("basePackages");
		Class<?>[] basePackageClasses = (Class<?>[]) attrs.get("basePackageClasses");
		scanPackages.addAll(Arrays.asList(value));
		scanPackages.addAll(Arrays.asList(basePackages));
		for (Class<?> clazz : basePackageClasses) {
			scanPackages
					.add(clazz.getName().substring(0, clazz.getName().lastIndexOf(".")));
		}
		if (scanPackages.isEmpty()) {
			// 没有显式指定扫描路径，则默认为@EnableRpcReferences 所注解的类所在路径
			String baseClassName = annotationMetadata.getClassName();
			scanPackages.add(baseClassName.substring(0, baseClassName.lastIndexOf(".")));
		}
		return scanPackages;
	}

	protected ClassPathScanningCandidateComponentProvider getScanner() {
		return new ClassPathScanningCandidateComponentProvider(false, this.environment) {
			@Override
			protected boolean isCandidateComponent(
					AnnotatedBeanDefinition beanDefinition) {
				boolean isCandidate = false;
				if (beanDefinition.getMetadata().isIndependent()) {
					if (!beanDefinition.getMetadata().isAnnotation()) {
						isCandidate = true;
					}
				}
				return isCandidate;
			}
		};
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	private String resolve(String value) {
		if (StringUtils.hasText(value)) {
			return this.environment.resolvePlaceholders(value);
		}
		return value;
	}

}

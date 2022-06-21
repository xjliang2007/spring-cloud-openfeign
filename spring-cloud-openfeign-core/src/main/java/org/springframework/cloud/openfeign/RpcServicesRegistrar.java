package org.springframework.cloud.openfeign;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * @author xiaojiang.lxj at 2022-06-21 14:45.
 */
public class RpcServicesRegistrar
		implements ImportBeanDefinitionRegistrar, EnvironmentAware, ResourceLoaderAware {

	private static final Logger log = LoggerFactory.getLogger(RpcServicesRegistrar.class);

	private ResourceLoader resourceLoader;

	private Environment environment;

	public static final String EUREKA_METADATA_MAP_PREFIX = "eureka.instance.metadataMap.";

	@Override
	public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata,
			BeanDefinitionRegistry registry) {
		registerRpcServices(importingClassMetadata, registry);
	}

	public void registerRpcServices(AnnotationMetadata metadata,
			BeanDefinitionRegistry registry) {
		ClassPathScanningCandidateComponentProvider scanner = getScanner();
		scanner.setResourceLoader(this.resourceLoader);
		AnnotationTypeFilter annotationTypeFilter = new AnnotationTypeFilter(
				RpcService.class);
		scanner.addIncludeFilter(annotationTypeFilter);
		Set<String> basePackages = getBasePackages(metadata);
		for (String basePackage : basePackages) {
			Set<BeanDefinition> candidateComponents = scanner
					.findCandidateComponents(basePackage);
			for (BeanDefinition candidateComponent : candidateComponents) {
				if (candidateComponent instanceof AnnotatedBeanDefinition) {
					AnnotatedBeanDefinition beanDefinition = (AnnotatedBeanDefinition) candidateComponent;
					registerService(beanDefinition);
				}
			}
		}
	}

	private void registerService(AnnotatedBeanDefinition beanDefinition) {
		Assert.hasText(beanDefinition.getBeanClassName(),
				"bean class name is empty! bean definition = " + beanDefinition);
		Class<?> clazz = null;
		try {
			clazz = ClassUtils.forName(beanDefinition.getBeanClassName(),
					ClassUtils.getDefaultClassLoader());
		}
		catch (ClassNotFoundException e) {
			log.error("register service failed! bean definition = " + beanDefinition, e);
		}
		Assert.notNull(clazz,
				"can not find bean class : " + beanDefinition.getBeanClassName());
		Class<?>[] interfaces = ClassUtils.getAllInterfacesForClass(clazz);
		Assert.notEmpty(interfaces,
				"class specified by @RpcService must implement an interface!");
		for (Class<?> interfaceClazz : interfaces) {
			if (interfaceClazz.isAnnotationPresent(RequestMapping.class)) {
				System.setProperty(
						EUREKA_METADATA_MAP_PREFIX + interfaceClazz.getCanonicalName(),
						Boolean.TRUE.toString());
			}
		}
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

	protected Set<String> getBasePackages(AnnotationMetadata importingClassMetadata) {
		Map<String, Object> attributes = importingClassMetadata
				.getAnnotationAttributes(EnableRpcServices.class.getCanonicalName());

		Set<String> basePackages = new HashSet<>();
		for (String pkg : (String[]) attributes.get("value")) {
			if (StringUtils.hasText(pkg)) {
				basePackages.add(pkg);
			}
		}
		for (String pkg : (String[]) attributes.get("basePackages")) {
			if (StringUtils.hasText(pkg)) {
				basePackages.add(pkg);
			}
		}
		for (Class<?> clazz : (Class[]) attributes.get("basePackageClasses")) {
			basePackages.add(ClassUtils.getPackageName(clazz));
		}

		if (basePackages.isEmpty()) {
			basePackages.add(
					ClassUtils.getPackageName(importingClassMetadata.getClassName()));
		}
		return basePackages;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

}

package org.springframework.cloud.openfeign.util;

import java.io.InputStream;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author xiaojiang.lxj at 2022-06-15 11:51.
 */
public class CommonUtils {

	private static final Logger log = LoggerFactory.getLogger(CommonUtils.class);

	public static String getAppNameFromJarFile(Class<?> markableClass) {
		try {
			final InputStream inputStream = (InputStream) markableClass
					.getProtectionDomain().getCodeSource().getLocation().getContent();
			JarInputStream jarInputStream = new JarInputStream(inputStream);
			Manifest manifest = jarInputStream.getManifest();
			return manifest.getMainAttributes().getValue("App-Name");
		}
		catch (final Exception e) {
			log.error("Unable to read MANIFEST.MF", e);
			return null;
		}
	}

}

package com.melloo.skymelloo.client.api;

import net.fabricmc.loader.api.FabricLoader;

/** Deployment this jar talks to, baked in at build time from the {@code site_url} Gradle property. */
public final class SiteConfig {
	public static final String PRODUCTION = "https://sky.melloo.me";

	private static final String ROOT = read();

	private SiteConfig() {
	}

	private static String read() {
		try {
			String value = FabricLoader.getInstance().getModContainer("skymelloo")
					.map(container -> container.getMetadata().getCustomValue("skymelloo:siteUrl"))
					.map(custom -> custom.getAsString())
					.orElse(PRODUCTION);
			if (value == null || value.isBlank()) {
				return PRODUCTION;
			}
			return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
		} catch (Exception e) {
			return PRODUCTION;
		}
	}

	/** Site root with no trailing slash, e.g. {@code https://sky.melloo.me}. */
	public static String root() {
		return ROOT;
	}

	public static String url(String path) {
		return ROOT + path;
	}

	/** True for anything other than production - shown in /sm version so a dev build is never mistaken for a real one. */
	public static boolean isCustomSite() {
		return !PRODUCTION.equals(ROOT);
	}
}

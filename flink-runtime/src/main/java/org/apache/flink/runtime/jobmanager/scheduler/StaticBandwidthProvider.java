package org.apache.flink.runtime.jobmanager.scheduler;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.clusterframework.types.GeoLocation;
import org.apache.flink.types.TwoKeysMap;
import org.apache.flink.types.TwoKeysMultiMap;

public class StaticBandwidthProvider implements BandwidthProvider {

	public static StaticBandwidthProvider fromFile() {
		return new StaticBandwidthProvider(new TwoKeysMultiMap<>());
	}

	private final TwoKeysMap<GeoLocation, GeoLocation, Double> bandwidths;

	public StaticBandwidthProvider(TwoKeysMap<GeoLocation, GeoLocation, Double> bandwidths) {
		this.bandwidths = bandwidths;
	}

	@Override
	public double getBandwidth(GeoLocation from, GeoLocation to) {
		if(bandwidths.containsKey(from ,to)) {
			return bandwidths.get(from ,to);
		} else {
			return 1;
		}
	}

	@Override
	public boolean hasBandwidth(GeoLocation locationFrom, GeoLocation locationTo) {
		return bandwidths.containsKey(locationFrom, locationTo);
	}
}

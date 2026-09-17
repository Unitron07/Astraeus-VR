# Roadmap

1. **Current: tracking feasibility.** Build the S24 ARCore tracker, independent UDP
   channel, Windows diagnostics and logs. Gather repeatable stationary drift,
   movement, tracking-loss, thermal and network measurements. Hardware acceptance
   remains pending; a successful build is not evidence of headset-quality tracking.
2. **Runtime pose bridge.** After evaluating measurements, expose a virtual HMD to
   SteamVR or an appropriate OpenXR runtime integration. OpenXR applications do
   not automatically gain a universal virtual-device driver. Investigate runtime
   requirements explicitly. No streamed video needed for this milestone.
3. **Stereo video transport.** Benchmark ALVR components, Sunshine/Moonlight
   components and custom approaches only where justified. Preserve tracking as a
   separate channel; retain pose IDs and timestamps used for rendering.
4. **Phone VR presentation.** Stereo eyes, lens profiles, IPD/FOV configuration,
   lens distortion, chromatic correction where needed, refresh synchronization.
5. **Latency measurement and optimization.** Instrument tracking, rendering,
   encoding, network, decoding and presentation separately. Establish clock
   synchronization/error bounds, then add and evaluate pose prediction.
6. **Phone-side rotational reprojection.** PC renders with pose A; encode, network,
   decode; acquire newest pose B; calculate A-to-B rotation; GPU reprojection;
   distortion; display. Pose/clock provenance must survive the whole pipeline.
7. **Controllers and other trackers.** Evaluate external/laptop webcams, IMUs,
   markers, dedicated tracking hardware and phone camera approaches. Route all
   devices through a generic tracking layer without assuming ARCore permanently
   defines the headset or controller implementation.

Milestones 2–7 are plans, not implemented features. Favor measured results over
theoretical latency optimizations. Decide after Milestone 1 whether ARCore's loss,
relocalization and motion behavior justify any runtime or streaming work.

(function (global) {
  'use strict';

  /**
   * Extensible multi-vehicle 3D GLB model registry.
   * Adding a new Chery model in the future only requires dropping its `.glb`
   * into `assets/car3d/models/` and registering a config entry below.
   */
  var registry = Object.create(null);
  var defaultKey = 'arrizo8';

  function register(key, config) {
    registry[String(key).toLowerCase()] = config;
  }

  // Primary model: 2023 Chery Arrizo 8 (real studio GLB)
  register('arrizo8', {
    key: 'arrizo8',
    displayName: '奇瑞 艾瑞泽 8',
    glbUrl: 'models/arrizo8.glb',
    targetLength: 4.78,
    yawOffset: 0, // Auto-calibrated after inspecting bounding box orientation
    keywords: ['arrizo', '艾瑞泽', 'a8', 'arrizo8']
  });

  function resolveKey(input) {
    if (!input) return defaultKey;
    var raw = String(input).trim().toLowerCase();
    if (registry[raw]) return raw;
    var keys = Object.keys(registry);
    for (var i = 0; i < keys.length; i++) {
      var cfg = registry[keys[i]];
      if (cfg.keywords) {
        for (var k = 0; k < cfg.keywords.length; k++) {
          if (raw.indexOf(cfg.keywords[k]) !== -1) {
            return cfg.key;
          }
        }
      }
    }
    return defaultKey;
  }

  global.CarModelRegistry = {
    register: register,
    resolveKey: resolveKey,
    get: function (keyOrName) {
      var resolved = resolveKey(keyOrName);
      return registry[resolved] || registry[defaultKey];
    },
    list: function () {
      return Object.keys(registry);
    }
  };
})(window);

(function (global) {
  'use strict';

  /**
   * Extensible multi-vehicle 3D GLB model registry.
   * Models are hosted in the Git repository (`models/`) via jsDelivr CDN and cached
   * permanently in Android local storage by VehicleModelRepository.
   */
  var registry = Object.create(null);
  var defaultKey = 'arrizo8';

  var suvFallbackKeywords = [
    '瑞虎', 'tiggo', '欧萌达', 'omoda', '探索', '大蚂蚁',
    '星纪元et', '星纪元 et', '风云t', '风云 t', '捷途', '山海',
    't19', 't22', 't26', 't1ej', 's61', 'e0y'
  ];

  function register(key, config) {
    registry[String(key).toLowerCase()] = config;
  }

  // Primary sedan model: Chery Arrizo 8
  register('arrizo8', {
    key: 'arrizo8',
    displayName: '奇瑞 艾瑞泽 8',
    bodyType: 'sedan',
    glbUrl: 'models/arrizo8.glb',
    targetLength: 4.78,
    wheelbase: 2.77,
    yawOffset: 0,
    headlightPos: [0.67, 0.77, 2.16],
    taillightPos: [0.66, 0.90, -2.26],
    hornPos: [0, 0.66, 2.38],
    keywords: ['arrizo8', 'arrizo 8', '艾瑞泽8', 'm1e', 'a8']
  });

  // Primary SUV model: 2025 Chery Tiggo 8
  register('tiggo8', {
    key: 'tiggo8',
    displayName: '奇瑞 瑞虎 8',
    bodyType: 'suv',
    glbUrl: 'models/tiggo8.glb',
    targetLength: 4.72,
    wheelbase: 2.71,
    yawOffset: 0,
    headlightPos: [0.76, 0.95, 2.02],
    taillightPos: [0.74, 1.13, -2.18],
    hornPos: [0, 0.76, 2.32],
    keywords: ['tiggo8', 'tiggo 8', '瑞虎8', 't18', 't1e', 't1d']
  });

  function resolveKey(input) {
    if (!input) return defaultKey;
    var raw = String(input).trim().toLowerCase();
    if (registry[raw]) return raw;

    // 1. Exact dedicated model keywords first
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

    // 2. Smart body-type fallback: SUV models fall back to tiggo8, sedans/unknown to arrizo8
    for (var s = 0; s < suvFallbackKeywords.length; s++) {
      if (raw.indexOf(suvFallbackKeywords[s]) !== -1) {
        return 'tiggo8';
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

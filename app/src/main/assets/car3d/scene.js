(function (global) {
  'use strict';

  var BG_HEX = 0x161719;
  var DEFAULT_THETA = 0.76;   // Classic front-left 3/4 hero angle
  var DEFAULT_PHI = 1.38;     // Sleek ~79° eye-level automotive showroom angle
  var CAR_CENTER_Y = 0.58;    // True optical vertical center of the 3D vehicle
  var HERO_RADIUS = 8.45;     // Initial fallback radius before first resize

  var renderer, scene, camera, envMap, gltfLoader;
  var currentContainer = null;
  var currentModelKey = null;
  var currentEffects = null;
  var renderActive = true;
  var rafId = null;
  var lastTime = 0;
  var elapsed = 0;
  var hasCustomDemoAngle = false;
  var hasNotifiedFirstDrag = false;
  var collapsedVisibleRatio = 0.64;
  var smoothDeckProgress = 0;
  var lastViewOffsetY = -1;
  var lastViewOffsetW = 0;
  var lastViewOffsetH = 0;

  var state = {
    modelKey: 'arrizo8',
    vehicleLocked: true,
    doorsOpen: false,
    trunkOpen: false,
    skylightOpen: false,
    climateOn: false,
    engineRunning: false
  };

  var orbit = {
    theta: DEFAULT_THETA,
    phi: DEFAULT_PHI,
    radius: HERO_RADIUS,
    targetTheta: DEFAULT_THETA,
    targetPhi: DEFAULT_PHI,
    targetRadius: HERO_RADIUS,
    targetCenter: new THREE.Vector3(0, CAR_CENTER_Y, 0.0),
    deckProgress: 0,
    deckStartTheta: DEFAULT_THETA,
    deckEndTheta: Math.PI,
    deckStartPhi: DEFAULT_PHI,
    velTheta: 0,
    velPhi: 0,
    lastMoveTime: 0,
    topDownLocked: false,
    dragging: false,
    startX: 0,
    startY: 0,
    lastX: 0,
    lastY: 0,
    movedDist: 0,
    lastTapTime: 0
  };

  function lerp(a, b, t) {
    return a + (b - a) * t;
  }

  function clamp(v, min, max) {
    return v < min ? min : (v > max ? max : v);
  }

  function smoothstep(edge0, edge1, x) {
    var t = clamp((x - edge0) / (edge1 - edge0), 0, 1);
    return t * t * (3 - 2 * t);
  }

  /**
   * High-contrast automotive photography studio HDR softbox environment map
   * so metallic paint, chrome grille, and tinted glass gleam cleanly on #161719.
   */
  function createStudioEnvironment(webglRenderer) {
    var pmrem = new THREE.PMREMGenerator(webglRenderer);
    pmrem.compileEquirectangularShader();

    var envScene = new THREE.Scene();
    envScene.background = new THREE.Color(0x1f2430);

    // Overhead main studio softbox
    var topBox = new THREE.Mesh(
      new THREE.PlaneGeometry(16, 24),
      new THREE.MeshBasicMaterial({ color: 0xffffff, side: THREE.DoubleSide })
    );
    topBox.position.set(0, 7.2, 0);
    topBox.rotation.x = Math.PI / 2;
    envScene.add(topBox);

    // Left key light softbox strip
    var leftStrip = new THREE.Mesh(
      new THREE.PlaneGeometry(18, 3.6),
      new THREE.MeshBasicMaterial({ color: 0xf4f8ff, side: THREE.DoubleSide })
    );
    leftStrip.position.set(7.2, 2.8, 1.5);
    leftStrip.rotation.y = -Math.PI / 2;
    envScene.add(leftStrip);

    // Right fill softbox strip
    var rightStrip = new THREE.Mesh(
      new THREE.PlaneGeometry(18, 3.2),
      new THREE.MeshBasicMaterial({ color: 0xe2ecff, side: THREE.DoubleSide })
    );
    rightStrip.position.set(-7.2, 2.6, -1.0);
    rightStrip.rotation.y = Math.PI / 2;
    envScene.add(rightStrip);

    // Front horizon highlight bar (illuminates grille & hood contours)
    var frontBar = new THREE.Mesh(
      new THREE.PlaneGeometry(14, 2.8),
      new THREE.MeshBasicMaterial({ color: 0xffffff, side: THREE.DoubleSide })
    );
    frontBar.position.set(0, 2.2, 9.0);
    envScene.add(frontBar);

    // Rear rim highlight bar (outlines fastback & tail)
    var rearBar = new THREE.Mesh(
      new THREE.PlaneGeometry(14, 2.4),
      new THREE.MeshBasicMaterial({ color: 0xdbe6f8, side: THREE.DoubleSide })
    );
    rearBar.position.set(0, 2.4, -9.0);
    envScene.add(rearBar);

    // Subtle lower bounce strip so lower doors and wheels stay legible
    var lowerRing = new THREE.Mesh(
      new THREE.CylinderGeometry(10, 10, 1.4, 32, 1, true),
      new THREE.MeshBasicMaterial({ color: 0x6b778c, side: THREE.BackSide })
    );
    lowerRing.position.y = 0.5;
    envScene.add(lowerRing);

    var rt = pmrem.fromScene(envScene, 0.03);
    pmrem.dispose();
    return rt.texture;
  }

  function createContactShadowTexture() {
    var canvas = document.createElement('canvas');
    canvas.width = 512;
    canvas.height = 1024;
    var ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    ctx.save();
    ctx.translate(256, 512);
    ctx.scale(1.0, 2.02);
    var g1 = ctx.createRadialGradient(0, 0, 50, 0, 0, 240);
    g1.addColorStop(0.0, 'rgba(0, 0, 0, 0.92)');
    g1.addColorStop(0.45, 'rgba(0, 0, 0, 0.65)');
    g1.addColorStop(0.75, 'rgba(0, 0, 0, 0.20)');
    g1.addColorStop(1.0, 'rgba(0, 0, 0, 0.0)');
    ctx.fillStyle = g1;
    ctx.beginPath();
    ctx.arc(0, 0, 240, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();

    ctx.save();
    ctx.translate(256, 512);
    ctx.scale(0.82, 1.88);
    var g2 = ctx.createRadialGradient(0, 0, 20, 0, 0, 185);
    g2.addColorStop(0.0, 'rgba(0, 0, 0, 0.96)');
    g2.addColorStop(0.72, 'rgba(0, 0, 0, 0.72)');
    g2.addColorStop(1.0, 'rgba(0, 0, 0, 0.0)');
    ctx.fillStyle = g2;
    ctx.beginPath();
    ctx.arc(0, 0, 185, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();

    var tex = new THREE.CanvasTexture(canvas);
    tex.anisotropy = 4;
    return tex;
  }

  function createGlowTexture(innerRgb, outerRgb) {
    var canvas = document.createElement('canvas');
    canvas.width = 128;
    canvas.height = 128;
    var ctx = canvas.getContext('2d');
    var grad = ctx.createRadialGradient(64, 64, 2, 64, 64, 62);
    grad.addColorStop(0.0, 'rgba(' + innerRgb + ', 1.0)');
    grad.addColorStop(0.28, 'rgba(' + outerRgb + ', 0.65)');
    grad.addColorStop(0.65, 'rgba(' + outerRgb + ', 0.14)');
    grad.addColorStop(1.0, 'rgba(' + outerRgb + ', 0.0)');
    ctx.fillStyle = grad;
    ctx.fillRect(0, 0, 128, 128);
    return new THREE.CanvasTexture(canvas);
  }

  function createSleekLightBarTexture(coreRgb, edgeRgb) {
    var canvas = document.createElement('canvas');
    canvas.width = 256;
    canvas.height = 64;
    var ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, 256, 64);

    // Ultra-tight horizontal LED beam-lens strip (no round blob)
    ctx.save();
    ctx.translate(128, 32);
    ctx.scale(1.0, 0.24);
    var grad = ctx.createRadialGradient(0, 0, 4, 0, 0, 118);
    grad.addColorStop(0.0, 'rgba(' + coreRgb + ', 1.0)');
    grad.addColorStop(0.32, 'rgba(' + coreRgb + ', 0.92)');
    grad.addColorStop(0.65, 'rgba(' + edgeRgb + ', 0.36)');
    grad.addColorStop(1.0, 'rgba(' + edgeRgb + ', 0.0)');
    ctx.fillStyle = grad;
    ctx.beginPath();
    ctx.arc(0, 0, 118, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();

    return new THREE.CanvasTexture(canvas);
  }

  function createInteractiveOverlays(carRoot, gltfScene) {
    var headBarTex = createSleekLightBarTexture('240, 247, 255', '196, 224, 255');
    var tailBarTex = createSleekLightBarTexture('255, 42, 42', '255, 30, 30');

    var headBarSprites = [];
    var tailBarSprites = [];

    // Ultra-tight horizontal white headlamp & red taillight lens accents (zero ground reflections)
    for (var side = -1; side <= 1; side += 2) {
      var hb = new THREE.Sprite(new THREE.SpriteMaterial({
        map: headBarTex,
        color: 0xf0f7ff,
        transparent: true,
        opacity: 0.0,
        blending: THREE.AdditiveBlending,
        depthWrite: false
      }));
      hb.position.set(side * 0.67, 0.77, 2.16);
      hb.scale.set(0.34, 0.085, 1);
      carRoot.add(hb);
      headBarSprites.push(hb);

      var tb = new THREE.Sprite(new THREE.SpriteMaterial({
        map: tailBarTex,
        color: 0xff1e1e,
        transparent: true,
        opacity: 0.0,
        blending: THREE.AdditiveBlending,
        depthWrite: false
      }));
      tb.position.set(side * 0.66, 0.90, -2.26);
      tb.scale.set(0.34, 0.075, 1);
      carRoot.add(tb);
      tailBarSprites.push(tb);
    }

    // 3D climate airflow ribbons above cabin roof
    var airflowGroup = new THREE.Group();
    airflowGroup.visible = false;
    var ribbons = [];
    for (var i = 0; i < 3; i++) {
      var xOff = (i - 1) * 0.34;
      var curve = new THREE.CatmullRomCurve3([
        new THREE.Vector3(xOff, 1.12, 0.95),
        new THREE.Vector3(xOff * 0.95, 1.62, 0.25),
        new THREE.Vector3(xOff * 0.92, 1.68, -0.50),
        new THREE.Vector3(xOff * 0.98, 1.48, -1.25)
      ]);
      var mat = new THREE.MeshBasicMaterial({
        color: 0x60a5fa,
        transparent: true,
        opacity: 0.55,
        blending: THREE.AdditiveBlending,
        depthWrite: false
      });
      var mesh = new THREE.Mesh(new THREE.TubeGeometry(curve, 36, 0.013, 8, false), mat);
      airflowGroup.add(mesh);
      ribbons.push({ mesh: mesh, mat: mat, phaseOffset: i * 1.1 });
    }
    carRoot.add(airflowGroup);

    // Locate sunroof, front headlamp/DRL, and rear taillight materials inside the real GLB
    var sunroofNode = null;
    var sunroofBasePos = null;
    var headMats = [];
    var tailMats = [];
    gltfScene.traverse(function (obj) {
      if (obj.name && obj.name.toLowerCase().indexOf('sunroof') !== -1 && !sunroofNode) {
        sunroofNode = obj;
        sunroofBasePos = obj.position.clone();
      }
      if (obj.isMesh && obj.material) {
        var isFrontLampMesh = obj.name && obj.name.indexOf('mesh_9_ar8') !== -1;
        if (isFrontLampMesh && !Array.isArray(obj.material)) {
          // Clone shared chrome/reflector material on front headlamp mesh so only front lamps glow
          obj.material = obj.material.clone();
          headMats.push(obj.material);
        }
        var mats = Array.isArray(obj.material) ? obj.material : [obj.material];
        for (var m = 0; m < mats.length; m++) {
          var mt = mats[m];
          var isRedTail = (mt.name && mt.name.indexOf('phong10SG1') !== -1) ||
            (mt.color && mt.color.r > 0.4 && mt.color.g < 0.12 && mt.color.b < 0.12);
          if (isRedTail && tailMats.indexOf(mt) === -1) {
            tailMats.push(mt);
          }
        }
      }
    });

    var anim = {
      flashActive: false,
      flashElapsed: 0,
      flashLevel: 0,
      sunroofProgress: 0
    };

    // Crisp 2-flash unlocking cadence: 180ms ON -> 140ms OFF -> 180ms ON -> 220ms smooth fade-out (0.72s total)
    var FLASH_TOTAL = 0.72;

    function sampleDoubleFlash(tSec) {
      if (tSec < 0 || tSec >= FLASH_TOTAL) return 0;
      if (tSec < 0.02) return tSec / 0.02;                // 20ms crisp LED attack
      if (tSec < 0.18) return 1.0;                        // Flash 1 hold
      if (tSec < 0.32) return 0.0;                        // 140ms clean off gap
      if (tSec < 0.34) return (tSec - 0.32) / 0.02;       // 20ms crisp LED attack
      if (tSec < 0.50) return 1.0;                        // Flash 2 hold
      var decay = (tSec - 0.50) / 0.22;                   // 220ms smooth fade back to rest
      return Math.max(0, 1.0 - decay * decay);
    }

    return {
      triggerFlash: function () {
        anim.flashActive = true;
        anim.flashElapsed = 0;
      },
      update: function (dt, t, st) {
        var smooth = 1.0 - Math.exp(-dt * 7.0);

        if (anim.flashActive) {
          anim.flashElapsed += dt;
          if (anim.flashElapsed >= FLASH_TOTAL) {
            anim.flashActive = false;
            anim.flashLevel = 0;
          } else {
            anim.flashLevel = sampleDoubleFlash(anim.flashElapsed);
          }
        } else {
          anim.flashLevel = 0;
        }

        // Front pure cool-white (#F0F7FF) headlamp/DRL emissive + tight lens bar
        var headRest = st.engineRunning ? 0.18 : 0.0;
        var headLevel = Math.min(1.0, headRest + anim.flashLevel);
        for (var hm = 0; hm < headMats.length; hm++) {
          if (headMats[hm].emissive) {
            headMats[hm].emissive.setHex(0xf0f7ff);
            headMats[hm].emissiveIntensity = headLevel * 1.45;
          }
        }
        var headBarOp = anim.flashLevel * 0.92 + (st.engineRunning ? 0.22 : 0.0);
        for (var hbIdx = 0; hbIdx < headBarSprites.length; hbIdx++) {
          headBarSprites[hbIdx].material.opacity = clamp(headBarOp, 0, 0.95);
        }

        // Rear pure red (#FF1E1E) taillight emissive + ultra-subtle tight red bar
        var tailBase = (st.engineRunning ? 0.30 : 0.0) + (st.vehicleLocked ? 0.0 : 0.12);
        var tailIntensity = 0.15 + tailBase + anim.flashLevel * 1.65;
        for (var tm = 0; tm < tailMats.length; tm++) {
          if (tailMats[tm].emissive) {
            tailMats[tm].emissive.setHex(0xff1e1e);
            tailMats[tm].emissiveIntensity = tailIntensity;
          }
        }
        var tailBarOp = anim.flashLevel * 0.68 + (st.engineRunning ? 0.12 : 0.0);
        for (var tbIdx = 0; tbIdx < tailBarSprites.length; tbIdx++) {
          tailBarSprites[tbIdx].material.opacity = clamp(tailBarOp, 0, 0.75);
        }

        // Sunroof smooth open/vent offset
        if (sunroofNode && sunroofBasePos) {
          var targetSky = st.skylightOpen ? 1.0 : 0.0;
          anim.sunroofProgress = lerp(anim.sunroofProgress, targetSky, smooth);
          sunroofNode.position.y = sunroofBasePos.y + anim.sunroofProgress * 0.06;
        }

        // Climate 3D airflow ribbons
        airflowGroup.visible = !!st.climateOn;
        if (st.climateOn) {
          for (var r = 0; r < ribbons.length; r++) {
            var rb = ribbons[r];
            var pulse = 0.5 + 0.5 * Math.sin(t * 3.4 + rb.phaseOffset);
            rb.mat.opacity = 0.22 + pulse * 0.48;
            rb.mesh.position.y = Math.sin(t * 2.2 + rb.phaseOffset) * 0.03;
          }
        }
      }
    };
  }

  function loadModel(modelKey) {
    var cfg = global.CarModelRegistry.get(modelKey);
    if (currentModelKey === cfg.key && currentContainer) {
      return;
    }
    currentModelKey = cfg.key;
    state.modelKey = cfg.key;

    if (currentContainer) {
      scene.remove(currentContainer);
      currentContainer = null;
      currentEffects = null;
    }

    var container = new THREE.Group();
    container.name = 'car_container_' + cfg.key;
    currentContainer = container;
    scene.add(container);

    // Contact shadow on Y=0
    var shadowTex = createContactShadowTexture();
    var shadowPlane = new THREE.Mesh(
      new THREE.PlaneGeometry(3.05, 6.05).rotateX(-Math.PI / 2),
      new THREE.MeshBasicMaterial({ map: shadowTex, transparent: true, opacity: 0.94, depthWrite: false })
    );
    shadowPlane.position.y = 0.002;
    container.add(shadowPlane);

    gltfLoader.load(
      cfg.glbUrl,
      function (gltf) {
        if (currentContainer !== container) return;
        var model = gltf.scene;

        // Enhance PBR materials with our studio environment map
        model.traverse(function (child) {
          if (child.isMesh && child.material) {
            var mats = Array.isArray(child.material) ? child.material : [child.material];
            for (var i = 0; i < mats.length; i++) {
              var mat = mats[i];
              mat.envMap = envMap;
              mat.envMapIntensity = 1.65;
              // Gently lift very dark metallic paint readability on dark #161719 backdrop
              if (mat.metalness > 0.65 && mat.roughness < 0.12 && mat.color) {
                mat.envMapIntensity = 2.1;
              }
              mat.needsUpdate = true;
            }
          }
        });

        // Compute raw bounding box to normalize scale, orientation, and ground contact
        var box = new THREE.Box3().setFromObject(model);
        var size = new THREE.Vector3();
        box.getSize(size);

        // Ensure vehicle length aligns with Z axis
        if (size.x > size.z * 1.15) {
          model.rotation.y += Math.PI / 2;
        }
        if (cfg.yawOffset) {
          model.rotation.y += cfg.yawOffset;
        }

        // Recompute bounds after orientation alignment
        model.updateMatrixWorld(true);
        box.setFromObject(model);
        box.getSize(size);
        var maxHorizontal = Math.max(size.x, size.z, 0.001);
        var targetLen = cfg.targetLength || 4.78;
        var scale = targetLen / maxHorizontal;
        model.scale.multiplyScalar(scale);

        // Center horizontally at (0, 0) and rest wheels flush on Y=0
        model.updateMatrixWorld(true);
        box.setFromObject(model);
        var center = new THREE.Vector3();
        box.getCenter(center);
        model.position.x -= center.x;
        model.position.z -= center.z;
        model.position.y -= box.min.y;

        container.add(model);
        currentEffects = createInteractiveOverlays(container, model);
        resize();

        // Restrained entrance arrival glide into hero 3/4 angle
        if (!hasCustomDemoAngle) {
          orbit.velTheta = 0;
          orbit.velPhi = 0;
          orbit.theta = DEFAULT_THETA + 0.24;
          orbit.targetTheta = DEFAULT_THETA;
          orbit.targetPhi = DEFAULT_PHI;
          updateCameraPosition();
        }

        notifyAndroidReady();
        requestAnimationFrame(function () {
          if (renderer && scene && camera) {
            renderer.render(scene, camera);
          }
          notifyAndroidFirstFrame();
        });
      },
      undefined,
      function (err) {
        if (global.Car3DBridge && typeof global.Car3DBridge.onWebGLError === 'function') {
          global.Car3DBridge.onWebGLError('GLB load failed: ' + String(err));
        }
      }
    );
  }

  function updateCameraPosition() {
    var sinPhi = Math.sin(orbit.phi);
    camera.position.set(
      orbit.targetCenter.x + orbit.radius * sinPhi * Math.sin(orbit.theta),
      orbit.targetCenter.y + orbit.radius * Math.cos(orbit.phi),
      orbit.targetCenter.z + orbit.radius * sinPhi * Math.cos(orbit.theta)
    );
    camera.lookAt(orbit.targetCenter);
  }

  function notifyAndroidTap() {
    try {
      if (global.Car3DBridge && typeof global.Car3DBridge.onVehicleTap === 'function') {
        global.Car3DBridge.onVehicleTap();
      }
    } catch (e) {
      // ignore in desktop preview
    }
  }

  function notifyAndroidDoubleTap() {
    try {
      if (global.Car3DBridge && typeof global.Car3DBridge.onVehicleDoubleTap === 'function') {
        global.Car3DBridge.onVehicleDoubleTap();
      }
    } catch (e) {
      // ignore in desktop preview
    }
  }

  var pendingSingleTapTimer = null;
  function dispatchVehicleTap() {
    if (pendingSingleTapTimer !== null) {
      clearTimeout(pendingSingleTapTimer);
      pendingSingleTapTimer = null;
      notifyAndroidDoubleTap();
    } else {
      pendingSingleTapTimer = setTimeout(function () {
        pendingSingleTapTimer = null;
        notifyAndroidTap();
      }, 260);
    }
  }

  function notifyAndroidReady() {
    try {
      if (global.Car3DBridge && typeof global.Car3DBridge.onSceneReady === 'function') {
        global.Car3DBridge.onSceneReady(state.modelKey);
      }
    } catch (e) {
      // ignore in desktop preview
    }
  }

  function notifyAndroidFirstFrame() {
    try {
      if (global.Car3DBridge && typeof global.Car3DBridge.onFirstFrameRendered === 'function') {
        global.Car3DBridge.onFirstFrameRendered();
      }
    } catch (e) {
      // ignore in desktop preview
    }
  }

  function notifyAndroidFirstDrag() {
    if (hasNotifiedFirstDrag) return;
    hasNotifiedFirstDrag = true;
    try {
      if (global.Car3DBridge && typeof global.Car3DBridge.onFirstOrbitDrag === 'function') {
        global.Car3DBridge.onFirstOrbitDrag();
      }
    } catch (e) {
      // ignore in desktop preview
    }
  }

  function clampPhi(p) {
    return clamp(p, 0.14, 1.44); // Allows clean top-down view without flipping or clipping
  }

  function bindPointerControls(domEl) {
    // Single-finger 360° orbit with physical angular momentum + 260ms single/double tap detection
    var touchTrackingTap = false;
    domEl.addEventListener('touchstart', function (e) {
      if (e.touches.length === 1) {
        touchTrackingTap = true;
        orbit.startX = orbit.lastX = e.touches[0].clientX;
        orbit.startY = orbit.lastY = e.touches[0].clientY;
        orbit.movedDist = 0;
        if (orbit.topDownLocked) {
          orbit.dragging = false;
          return;
        }
        orbit.dragging = true;
        orbit.velTheta = 0;
        orbit.velPhi = 0;
        orbit.lastMoveTime = performance.now();
      } else {
        touchTrackingTap = false;
        orbit.dragging = false;
      }
    }, { passive: true });

    domEl.addEventListener('touchmove', function (e) {
      if (e.touches.length !== 1) return;
      var cx = e.touches[0].clientX;
      var cy = e.touches[0].clientY;
      var dx = cx - orbit.lastX;
      var dy = cy - orbit.lastY;
      orbit.lastX = cx;
      orbit.lastY = cy;
      orbit.movedDist += Math.abs(dx) + Math.abs(dy);

      if (orbit.topDownLocked || !orbit.dragging) return;
      var now = performance.now();
      var moveDt = Math.max(0.008, Math.min(0.05, (now - orbit.lastMoveTime) / 1000));
      orbit.lastMoveTime = now;

      var dTheta = -dx * 0.0085;
      var dPhi = -dy * 0.0055;
      orbit.targetTheta += dTheta;
      orbit.targetPhi = clampPhi(orbit.targetPhi + dPhi);
      if (orbit.movedDist >= 8) {
        notifyAndroidFirstDrag();
      }

      orbit.velTheta = lerp(orbit.velTheta, clamp(dTheta / moveDt, -7.5, 7.5), 0.45);
      orbit.velPhi = lerp(orbit.velPhi, clamp(dPhi / moveDt, -3.5, 3.5), 0.45);
    }, { passive: true });

    domEl.addEventListener('touchend', function (e) {
      if (e.touches.length === 0) {
        var idleMs = performance.now() - orbit.lastMoveTime;
        if (touchTrackingTap && orbit.movedDist < 12) {
          orbit.velTheta = 0;
          orbit.velPhi = 0;
          dispatchVehicleTap();
        } else if (orbit.dragging && idleMs > 80) {
          orbit.velTheta = 0;
          orbit.velPhi = 0;
        }
        touchTrackingTap = false;
        orbit.dragging = false;
      }
    }, { passive: true });

    var mouseTrackingTap = false;
    domEl.addEventListener('mousedown', function (e) {
      mouseTrackingTap = true;
      orbit.startX = orbit.lastX = e.clientX;
      orbit.startY = orbit.lastY = e.clientY;
      orbit.movedDist = 0;
      if (orbit.topDownLocked) {
        orbit.dragging = false;
        return;
      }
      orbit.dragging = true;
      orbit.velTheta = 0;
      orbit.velPhi = 0;
      orbit.lastMoveTime = performance.now();
    });
    window.addEventListener('mousemove', function (e) {
      if (!mouseTrackingTap && !orbit.dragging) return;
      var dx = e.clientX - orbit.lastX;
      var dy = e.clientY - orbit.lastY;
      orbit.lastX = e.clientX;
      orbit.lastY = e.clientY;
      orbit.movedDist += Math.abs(dx) + Math.abs(dy);

      if (orbit.topDownLocked || !orbit.dragging) return;
      var now = performance.now();
      var moveDt = Math.max(0.008, Math.min(0.05, (now - orbit.lastMoveTime) / 1000));
      orbit.lastMoveTime = now;

      var dTheta = -dx * 0.008;
      var dPhi = -dy * 0.005;
      orbit.targetTheta += dTheta;
      orbit.targetPhi = clampPhi(orbit.targetPhi + dPhi);
      if (orbit.movedDist >= 8) {
        notifyAndroidFirstDrag();
      }

      orbit.velTheta = lerp(orbit.velTheta, clamp(dTheta / moveDt, -7.5, 7.5), 0.45);
      orbit.velPhi = lerp(orbit.velPhi, clamp(dPhi / moveDt, -3.5, 3.5), 0.45);
    });
    window.addEventListener('mouseup', function () {
      if (!mouseTrackingTap && !orbit.dragging) return;
      var wasDragging = orbit.dragging;
      var wasTap = mouseTrackingTap && orbit.movedDist < 8;
      mouseTrackingTap = false;
      orbit.dragging = false;
      var idleMs = performance.now() - orbit.lastMoveTime;
      if (wasTap) {
        orbit.velTheta = 0;
        orbit.velPhi = 0;
        dispatchVehicleTap();
      } else if (wasDragging && idleMs > 80) {
        orbit.velTheta = 0;
        orbit.velPhi = 0;
      }
    });
  }

  function computeDynamicRadius(phi, aspect, visRatio) {
    var vr = clamp(typeof visRatio === 'number' ? visRatio : 1.0, 0.40, 1.0);
    var vFovRad = (camera ? camera.fov : 34) * Math.PI / 180;
    var halfV = vFovRad / 2;
    var effectiveHalfV = Math.atan(Math.tan(halfV) * vr);
    var hFovRad = 2 * Math.atan(Math.tan(halfV) * Math.max(aspect, 0.6));

    // Top-down vertical fit strictly inside visible window [0, vr * h] (zero bottom occlusion)
    var rTop = 2.72 / Math.sin(effectiveHalfV);
    // 3/4 hero fit: ~80% horizontal fill + balanced margin inside visible window
    var distH = 2.74 / Math.sin(hFovRad / 2);
    var rHero = Math.max(6.4 / Math.max(vr, 0.72), distH);

    // Smooth phi-driven pullback from 3/4 hero (phi=1.24) to top-down (phi=0.14)
    var topFactor = smoothstep(DEFAULT_PHI, 0.14, phi);
    return lerp(rHero, rTop, topFactor);
  }

  var lastResizeW = 0;
  var lastResizeH = 0;

  function applyCameraViewOffset() {
    if (!camera) return;
    var w = lastResizeW || window.innerWidth || 360;
    var h = lastResizeH || window.innerHeight || 256;
    var visRatio = lerp(collapsedVisibleRatio, 1.0, smoothDeckProgress);
    var yOff = Math.round((1.0 - visRatio) * h * 0.5);
    if (yOff !== lastViewOffsetY || w !== lastViewOffsetW || h !== lastViewOffsetH) {
      lastViewOffsetY = yOff;
      lastViewOffsetW = w;
      lastViewOffsetH = h;
      if (yOff <= 0) {
        camera.clearViewOffset();
      } else {
        camera.setViewOffset(w, h, 0, yOff, w, h);
      }
    }
  }

  function resize() {
    if (!renderer || !camera) return;
    var el = document.getElementById('stage');
    var w = (el && el.clientWidth) || window.innerWidth || 360;
    var h = (el && el.clientHeight) || window.innerHeight || 256;
    if (w <= 0 || h <= 0) return;
    if (w === lastResizeW && h === lastResizeH) return;
    lastResizeW = w;
    lastResizeH = h;
    var aspect = w / Math.max(1, h);
    camera.aspect = aspect;
    lastViewOffsetY = -1; // force viewOffset refresh with new dimensions
    applyCameraViewOffset();
    var visRatio = lerp(collapsedVisibleRatio, 1.0, smoothDeckProgress);
    var fitR = computeDynamicRadius(orbit.targetPhi, aspect, visRatio);
    orbit.targetRadius = fitR;
    if (elapsed < 0.1) {
      orbit.radius = fitR;
    }
    renderer.setSize(w, h, false);
    if (renderActive && scene) {
      renderer.render(scene, camera);
    }
  }

  function animate(now) {
    if (!renderActive) {
      rafId = null;
      return;
    }
    rafId = requestAnimationFrame(animate);

    var dt = lastTime ? Math.min(0.05, (now - lastTime) / 1000) : 0.016;
    lastTime = now;
    elapsed += dt;

    // Physical angular momentum inertia with exponential turntable friction
    if (!orbit.dragging) {
      if (Math.abs(orbit.velTheta) > 0.001 || Math.abs(orbit.velPhi) > 0.001) {
        orbit.targetTheta += orbit.velTheta * dt;
        var nextPhi = clampPhi(orbit.targetPhi + orbit.velPhi * dt);
        if (nextPhi === 0.14 || nextPhi === 1.44) {
          orbit.velPhi = 0;
        }
        orbit.targetPhi = nextPhi;
        var friction = Math.exp(-dt * 4.8);
        orbit.velTheta *= friction;
        orbit.velPhi *= friction;
      } else {
        orbit.velTheta = 0;
        orbit.velPhi = 0;
      }
    }

    var damp = 1.0 - Math.exp(-dt * 9.5);
    smoothDeckProgress += (orbit.deckProgress - smoothDeckProgress) * damp;
    if (Math.abs(orbit.deckProgress - smoothDeckProgress) < 0.0005) {
      smoothDeckProgress = orbit.deckProgress;
    }
    var visRatio = lerp(collapsedVisibleRatio, 1.0, smoothDeckProgress);
    applyCameraViewOffset();

    if (camera) {
      orbit.targetRadius = computeDynamicRadius(orbit.targetPhi, camera.aspect, visRatio);
    }

    orbit.theta += (orbit.targetTheta - orbit.theta) * damp;
    orbit.phi += (orbit.targetPhi - orbit.phi) * damp;
    orbit.radius += (orbit.targetRadius - orbit.radius) * damp;
    updateCameraPosition();

    if (currentEffects && typeof currentEffects.update === 'function') {
      currentEffects.update(dt, elapsed, state);
    }

    renderer.render(scene, camera);
  }

  function init() {
    var container = document.getElementById('stage');
    try {
      renderer = new THREE.WebGLRenderer({
        antialias: true,
        alpha: false,
        powerPreference: 'high-performance'
      });
    } catch (err) {
      if (global.Car3DBridge && typeof global.Car3DBridge.onWebGLError === 'function') {
        global.Car3DBridge.onWebGLError(String(err));
      }
      return;
    }

    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2.2));
    renderer.setClearColor(BG_HEX, 1);
    renderer.outputEncoding = THREE.sRGBEncoding;
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.25;
    container.appendChild(renderer.domElement);

    scene = new THREE.Scene();
    scene.background = new THREE.Color(BG_HEX);

    camera = new THREE.PerspectiveCamera(34, (window.innerWidth || 360) / Math.max(1, window.innerHeight || 256), 0.1, 60);

    envMap = createStudioEnvironment(renderer);
    scene.environment = envMap;

    // Studio key, fill, rim & under-bounce lights
    scene.add(new THREE.HemisphereLight(0xf2f6ff, 0x262c38, 1.05));

    var keyLight = new THREE.DirectionalLight(0xffffff, 1.55);
    keyLight.position.set(5.0, 7.0, 5.5);
    scene.add(keyLight);

    var rimLight = new THREE.DirectionalLight(0xcde0ff, 1.15);
    rimLight.position.set(-5.5, 4.2, -5.5);
    scene.add(rimLight);

    var frontFill = new THREE.DirectionalLight(0xe6f0ff, 0.75);
    frontFill.position.set(-2.5, 2.0, 6.5);
    scene.add(frontFill);

    gltfLoader = new THREE.GLTFLoader();

    // Optional preview query params
    try {
      var params = new URLSearchParams(window.location.search || '');
      var demo = params.get('demo');
      if (demo === 'rear') {
        hasCustomDemoAngle = true;
        orbit.theta = orbit.targetTheta = -2.36;
        orbit.phi = orbit.targetPhi = 1.24;
      } else if (demo === 'open') {
        hasCustomDemoAngle = true;
        state.vehicleLocked = false;
        state.doorsOpen = true;
        state.trunkOpen = true;
        state.skylightOpen = true;
        state.climateOn = true;
        state.engineRunning = true;
        orbit.theta = orbit.targetTheta = 0.84;
        orbit.phi = orbit.targetPhi = 1.20;
      }
    } catch (e) {
      // ignore
    }

    loadModel(state.modelKey);
    updateCameraPosition();
    resize();
    bindPointerControls(renderer.domElement);
    window.addEventListener('resize', resize);
    if (typeof ResizeObserver !== 'undefined' && container) {
      var ro = new ResizeObserver(function () {
        resize();
      });
      ro.observe(container);
    }

    lastTime = performance.now();
    animate(lastTime);
  }

  global.Car3D = {
    setState: function (patch) {
      if (!patch || typeof patch !== 'object') return;
      if (typeof patch.modelKey === 'string' && patch.modelKey) {
        loadModel(patch.modelKey);
      }
      if (typeof patch.vehicleLocked === 'boolean') state.vehicleLocked = patch.vehicleLocked;
      if (typeof patch.doorsOpen === 'boolean') state.doorsOpen = patch.doorsOpen;
      if (typeof patch.trunkOpen === 'boolean') state.trunkOpen = patch.trunkOpen;
      if (typeof patch.skylightOpen === 'boolean') state.skylightOpen = patch.skylightOpen;
      if (typeof patch.climateOn === 'boolean') state.climateOn = patch.climateOn;
      if (typeof patch.engineRunning === 'boolean') state.engineRunning = patch.engineRunning;
    },
    flashLights: function () {
      if (currentEffects && currentEffects.triggerFlash) {
        currentEffects.triggerFlash();
      }
    },
    resetCamera: function () {
      orbit.deckProgress = 0;
      orbit.topDownLocked = false;
      orbit.velTheta = 0;
      orbit.velPhi = 0;
      orbit.targetTheta = DEFAULT_THETA;
      orbit.targetPhi = DEFAULT_PHI;
    },
    setCollapsedVisibleRatio: function (ratio) {
      if (typeof ratio === 'number' && isFinite(ratio)) {
        collapsedVisibleRatio = clamp(ratio, 0.40, 1.0);
        lastViewOffsetY = -1;
        applyCameraViewOffset();
      }
    },
    setDeckProgress: function (progress) {
      var p = clamp(typeof progress === 'number' ? progress : 0, 0, 1);
      var prev = orbit.deckProgress;
      var twoPi = Math.PI * 2;

      if (prev <= 0.001 && p > 0.001) {
        orbit.deckStartTheta = orbit.theta;
        orbit.deckStartPhi = orbit.phi;
        var currentMod = ((orbit.theta % twoPi) + twoPi) % twoPi;
        var delta = Math.PI - currentMod;
        if (delta > Math.PI) delta -= twoPi;
        if (delta < -Math.PI) delta += twoPi;
        orbit.deckEndTheta = orbit.theta + delta;
      }

      orbit.deckProgress = p;
      orbit.velTheta = 0;
      orbit.velPhi = 0;

      if (p > 0.001) {
        orbit.topDownLocked = true;
        orbit.dragging = false;
        orbit.targetTheta = lerp(orbit.deckStartTheta, orbit.deckEndTheta, p);
        orbit.targetPhi = lerp(orbit.deckStartPhi, 0.05, p);
      } else {
        orbit.topDownLocked = false;
        if (prev > 0.001) {
          var curMod = ((orbit.theta % twoPi) + twoPi) % twoPi;
          var backDelta = DEFAULT_THETA - curMod;
          if (backDelta > Math.PI) backDelta -= twoPi;
          if (backDelta < -Math.PI) backDelta += twoPi;
          orbit.targetTheta = orbit.theta + backDelta;
          orbit.targetPhi = DEFAULT_PHI;
        }
      }
    },
    setTopDownFocus: function (enabled) {
      global.Car3D.setDeckProgress(enabled ? 1 : 0);
    },
    setCameraAngle: function (theta, phi) {
      orbit.velTheta = 0;
      orbit.velPhi = 0;
      if (typeof theta === 'number') orbit.targetTheta = theta;
      if (typeof phi === 'number') orbit.targetPhi = clampPhi(phi);
    },
    setRenderActive: function (active) {
      renderActive = !!active;
      if (renderActive && rafId === null && renderer) {
        lastTime = performance.now();
        animate(lastTime);
      }
    },
    resize: resize
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})(window);

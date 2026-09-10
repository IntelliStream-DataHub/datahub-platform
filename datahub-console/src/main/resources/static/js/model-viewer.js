/*
 * 3D model viewer, shown in a modal from the file-information dialog.
 *
 * Everything is CLIENT-SIDE against datahub-api, with no console backend-for-frontend
 * (CONSTRAINTS F2). The model comes back as a blob through window.Api rather than being handed to
 * the library as a URL, because the download route is authenticated and the library cannot attach
 * a bearer token.
 *
 *   GET {api}/files/download/{externalId} -> the model bytes
 *
 * The vendored library is a megabyte, so it is injected on first open rather than loaded with the
 * files page. Browsing files costs nothing until someone actually opens a model.
 *
 * Orbit is a left-drag, pan a right-drag, zoom the wheel; all three come from the library, which
 * listens on the canvas for mousedown/wheel and on document for mousemove/mouseup.
 */
window.ModelViewer = (function () {
	"use strict";

	// The default Api timeout is 10s (application.js), which silently aborts a multi-MB model on a
	// slow link. Same reason the drawing viewer raises it.
	var FILE_TIMEOUT_MS = 120000;
	var LIB_SRC = "/static/js/model-viewer/o3dv.min.js";

	/*
	 * A cube map is not decoration here. The viewer shades a model carrying PBR materials, which
	 * every CAD import does, with ambient light at zero and the scene's environment as the only
	 * source. Without one a converted plant model renders almost black.
	 */
	var ENVMAP = ["posx", "negx", "posy", "negy", "posz", "negz"].map(function (face) {
		return "/static/js/model-viewer/envmap/" + face + ".jpg";
	});

	var libPromise = null;

	// The extensions the vendored library imports. Decided on the extension rather than the mime
	// type: Tika calls most CAD application/octet-stream, and STEP is plain text so it can come
	// back as text/plain.
	var MODEL_PATTERN =
		/\.(3dm|3ds|3mf|amf|bim|brep|dae|fbx|fcstd|gltf|glb|ifc|iges|igs|step|stp|stl|obj|off|ply|wrl)$/i;

	function isModel(name) {
		return MODEL_PATTERN.test(name || "");
	}

	function loadLibrary() {
		if (libPromise) {
			return libPromise;
		}
		libPromise = new Promise(function (resolve, reject) {
			var script = document.createElement("script");
			script.src = LIB_SRC;
			script.onload = resolve;
			script.onerror = function () {
				libPromise = null;
				reject(new Error("viewer library failed to load"));
			};
			document.head.appendChild(script);
		});
		return libPromise;
	}

	/*
	 * The canvas paints on whatever the modal surface is, so read it off the element rather than
	 * hard-coding a colour per theme.
	 */
	function backgroundColor(el) {
		var parsed = /rgba?\(([^)]+)\)/.exec(getComputedStyle(el).backgroundColor);
		if (!parsed) {
			return new OV.RGBAColor(255, 255, 255, 255);
		}
		var parts = parsed[1].split(",").map(function (p) { return parseFloat(p.trim()); });
		return new OV.RGBAColor(parts[0], parts[1], parts[2],
			parts.length > 3 ? Math.round(parts[3] * 255) : 255);
	}

	// A model may name files beside it: an OBJ its material library and that library its textures,
	// a glTF its buffer and images. Only that many are ever fetched, and only from the model's own
	// folder, so a folder of unrelated files costs nothing.
	var MAX_COMPANIONS = 24;

	/** Filenames an .obj names in mtllib lines, and an .mtl in its map_* lines. */
	function objReferences(text) {
		var names = [];
		text.split(/\r?\n/).forEach(function (line) {
			var m = /^\s*(?:mtllib|map_[A-Za-z]+|bump|norm|disp|decal)\s+(.+?)\s*$/i.exec(line);
			if (m) {
				// A map line can carry options before the filename; the filename is the last token.
				var parts = m[1].split(/\s+/);
				names.push(parts[parts.length - 1]);
			}
		});
		return names;
	}

	/** Relative uris a .gltf names for its buffers and images, skipping inline data. */
	function gltfReferences(text) {
		var names = [];
		try {
			var doc = JSON.parse(text);
			["buffers", "images"].forEach(function (key) {
				(doc[key] || []).forEach(function (entry) {
					if (entry.uri && entry.uri.indexOf("data:") !== 0) {
						names.push(decodeURIComponent(entry.uri));
					}
				});
			});
		} catch (ignored) { /* a malformed glTF fails later, in the importer, with a real message */ }
		return names;
	}

	/**
	 * The files this model names, as File objects, resolved against its own folder.
	 *
	 * Reading the model to find its references, rather than sweeping the folder, means exactly the
	 * companions it asks for are fetched. Anything missing is skipped: a model with no material
	 * library still opens, just untextured, which is what it would have done anyway.
	 */
	function companionsOf(node, blob) {
		var name = node.name || "";
		var isObj = /\.obj$/i.test(name);
		var isGltf = /\.gltf$/i.test(name);
		if (!isObj && !isGltf) {
			return Promise.resolve([]);
		}
		var folder = (node.path || "").replace(/\/[^/]*$/, "");
		return Promise.all([blob.text(), listFolder(folder)]).then(function (both) {
			var wanted = isObj ? objReferences(both[0]) : gltfReferences(both[0]);
			var byName = both[1];
			return fetchNamed(wanted, byName, []).then(function (files) {
				// An .mtl names textures of its own, so resolve one level further.
				if (!isObj) {
					return files;
				}
				var texts = files.filter(function (f) { return /\.mtl$/i.test(f.name); });
				return Promise.all(texts.map(function (f) { return f.text(); })).then(function (bodies) {
					var more = [];
					bodies.forEach(function (b) { more = more.concat(objReferences(b)); });
					return fetchNamed(more, byName, files);
				});
			});
		}).catch(function () {
			return []; // the model itself still opens
		});
	}

	/** name -> externalId for one folder, so a referenced filename can be downloaded. */
	function listFolder(folder) {
		return window.Api.get("/files/list" + folder).then(function (response) {
			if (!response.ok) {
				return {};
			}
			return response.json().then(function (data) {
				var map = {};
				(data.items || []).forEach(function (item) {
					if (item.type === "FILE") {
						map[item.name] = item.externalId;
					}
				});
				return map;
			});
		});
	}

	function fetchNamed(names, byName, already) {
		var have = {};
		already.forEach(function (f) { have[f.name] = true; });
		var todo = names.filter(function (n) {
			var keep = !have[n] && byName[n];
			have[n] = true;
			return keep;
		}).slice(0, MAX_COMPANIONS - already.length);

		return Promise.all(todo.map(function (n) {
			return window.Api.request("/files/download/" + encodeURIComponent(byName[n]), {
				headers: { Accept: "*/*" },
				timeout: FILE_TIMEOUT_MS
			}).then(function (r) {
				return r.ok ? r.blob().then(function (b) { return new File([b], n); }) : null;
			}).catch(function () { return null; });
		})).then(function (fetched) {
			return already.concat(fetched.filter(Boolean));
		});
	}

	/* Streams the body so a large model reports progress instead of sitting on "Loading". */
	function readBody(response, onProgress) {
		var total = parseInt(response.headers.get("Content-Length") || "0", 10);
		if (!response.body || !total) {
			return response.blob();
		}
		var reader = response.body.getReader();
		var chunks = [];
		var received = 0;
		return (function pump() {
			return reader.read().then(function (result) {
				if (result.done) {
					return new Blob(chunks);
				}
				chunks.push(result.value);
				received += result.value.length;
				onProgress(Math.floor((received / total) * 100));
				return pump();
			});
		})();
	}

	function open(node) {
		var overlay = document.createElement("div");
		overlay.className = "dh-modal-overlay";
		overlay.innerHTML =
			'<div class="dh-modal pad20 dh-model-modal" role="dialog" aria-modal="true">'
			+ '<h2><i class="fa fa-fw fa-cube"></i><span></span></h2>'
			+ '<div class="dh-model-stage">'
			+   '<div class="dh-model-canvas" data-type="model-canvas"></div>'
			+   '<p class="dh-model-status" data-type="model-status"></p>'
			+ '</div>'
			+ '<div class="btns flex-end mtop20">'
			+ '<button type="button" class="dh-btn secondary" data-act="close"><span></span></button>'
			+ '<a class="dh-btn primary" data-act="download"><i class="fa fa-fw fa-download"></i> <span></span></a>'
			+ '</div></div>';
		document.body.appendChild(overlay);

		overlay.querySelector("h2 span").textContent = node.name || "";
		overlay.querySelector('[data-act="close"] span').textContent = $L("close");
		var download = overlay.querySelector('[data-act="download"]');
		download.href = "/files/download/" + encodeURIComponent(node.externalId);
		download.querySelector("span").textContent = $L("download");

		var canvasEl = overlay.querySelector('[data-type="model-canvas"]');
		var statusEl = overlay.querySelector('[data-type="model-status"]');
		var viewer = null;

		function status(message, isError) {
			statusEl.textContent = message || "";
			statusEl.hidden = !message;
			statusEl.classList.toggle("is-error", !!isError);
		}

		function failed() {
			status($L("model.load.failed"), true);
		}

		function onResize() {
			if (viewer) {
				viewer.Resize();
			}
		}

		function close() {
			// Destroy releases the WebGL context. Browsers cap how many a page may hold at once, so
			// opening a few models without this stops rendering entirely.
			if (viewer) {
				viewer.Destroy();
				viewer = null;
			}
			window.removeEventListener("resize", onResize);
			document.removeEventListener("keydown", onKey);
			overlay.remove();
		}

		function onKey(ev) {
			if (ev.key === "Escape") {
				close();
			}
		}

		document.addEventListener("keydown", onKey);
		window.addEventListener("resize", onResize);
		overlay.addEventListener("mousedown", function (ev) {
			// A drag that starts on the canvas and ends outside it must not close the dialog, so
			// only a press that lands on the backdrop itself counts.
			if (ev.target === overlay) {
				close();
			}
		});
		overlay.querySelector('[data-act="close"]').addEventListener("click", close);

		status($L("model.loading"));
		loadLibrary()
			.then(function () {
				return window.Api.request("/files/download/" + encodeURIComponent(node.externalId), {
					headers: { Accept: "*/*" },
					timeout: FILE_TIMEOUT_MS
				});
			})
			.then(function (response) {
				if (!response.ok) {
					return Promise.reject(response.status);
				}
				return readBody(response, function (percent) {
					status($L("model.loading.progress", null, [percent]));
				});
			})
			.then(function (blob) {
				if (!overlay.isConnected) {
					return null; // closed while it was still downloading
				}
				status($L("model.loading"));
				return companionsOf(node, blob).then(function (extra) {
					if (!overlay.isConnected) {
						return null;
					}
					viewer = new OV.EmbeddedViewer(canvasEl, {
						backgroundColor: backgroundColor(canvasEl),
						defaultColor: new OV.RGBColor(160, 168, 180),
						// false: light the model with it, but keep the dialog's own background.
						environmentSettings: new OV.EnvironmentSettings(ENVMAP, false),
						onModelLoaded: function () { status(null); },
						onModelLoadFailed: failed
					});
					// The library picks its importer from the extension and resolves a model's
					// references by filename across the list, so both have to carry real names.
					viewer.LoadModelFromFileList([new File([blob], node.name)].concat(extra));
				});
			})
			.catch(function (reason) {
				// 404 covers "no such file" and "not yours to read" alike: the api hides a file
				// outside the caller's readable datasets rather than admitting it exists.
				status(reason === 404 ? $L("model.not.found") : $L("model.load.failed"), true);
			});
	}

	return { isModel: isModel, open: open };
})();

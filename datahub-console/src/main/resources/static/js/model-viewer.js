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
					return; // closed while it was still downloading
				}
				viewer = new OV.EmbeddedViewer(canvasEl, {
					backgroundColor: backgroundColor(canvasEl),
					defaultColor: new OV.RGBColor(160, 168, 180),
					onModelLoaded: function () { status(null); },
					onModelLoadFailed: failed
				});
				// The library picks its importer from the extension, so the blob has to carry the
				// real filename.
				viewer.LoadModelFromFileList([new File([blob], node.name)]);
			})
			.catch(function (reason) {
				// 404 covers "no such file" and "not yours to read" alike: the api hides a file
				// outside the caller's readable datasets rather than admitting it exists.
				status(reason === 404 ? $L("model.not.found") : $L("model.load.failed"), true);
			});
	}

	return { isModel: isModel, open: open };
})();

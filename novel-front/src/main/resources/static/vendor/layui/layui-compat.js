/* Expose bundled Layui components for existing application code. */
(function (ui) {
  'use strict';
  if (!ui) return;
  window.layer = ui.layer;
  // All modern Layui modules must share the already loaded jQuery instance.
  if (window.jQuery) ui.jquery = ui.$ = window.jQuery;
  // Retained date fields use the former callable laydate API.
  function legacyDate(options) {
    options = Object.assign({}, options);
    if (options.choose && !options.done) options.done = options.choose;
    if (options.format) options.format = options.format.replace(/YYYY/g, 'yyyy').replace(/DD/g, 'dd').replace(/hh/g, 'HH');
    if (options.istime && /HH/.test(options.format || '')) options.type = 'datetime';
    if (!options.elem) { options.elem = document.activeElement; options.show = true; }
    return ui.laydate.render(options);
  }
  Object.keys(ui.laydate).forEach(function (key) { legacyDate[key] = ui.laydate[key]; });
  window.laydate = legacyDate;
})(window.layui);

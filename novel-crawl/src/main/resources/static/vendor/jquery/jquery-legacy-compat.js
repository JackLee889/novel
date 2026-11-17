/* Retained plugins still use APIs removed before jQuery 4. */
(function ($) {
  'use strict';
  if (!$ || $.novelLegacyCompat) return;
  $.novelLegacyCompat = true;
  $.parseJSON = JSON.parse;
  $.fn.size = function () { return this.length; };
  $.fn.andSelf = $.fn.addBack;
  // Old plugins attach image/window load handlers through these aliases.
  var load = $.fn.load;
  $.fn.load = function (url) {
    return typeof url === 'function' ? this.on('load', url) : load.apply(this, arguments);
  };
  $.fn.error = function (handler) { return this.on('error', handler); };
  $.fn.unload = function (handler) { return this.on('unload', handler); };
  // jQuery Validate 1.13 remote rules expect the old jqXHR aliases.
  $.ajaxPrefilter(function (options, original, xhr) {
    xhr.success = xhr.done;
    xhr.error = xhr.fail;
    xhr.complete = function (callback) {
      if (callback) options.complete = [options.complete, callback].filter(Boolean);
      return xhr;
    };
  });
})(window.jQuery);

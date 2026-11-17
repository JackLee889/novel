/* App adapters for Bootstrap 5.3.8 and the retained Bootstrap Table / MetisMenu plugins.
 * The upstream bundle and other vendor files stay unmodified.
 */
(function (window, document) {
    'use strict';
    var $ = window.jQuery, bootstrap = window.bootstrap;
    if (!$ || !bootstrap) { return; }

    function each(root, selector, callback) {
        if (root.nodeType === 1 && root.matches(selector)) { callback(root); }
        if (root.querySelectorAll) { Array.prototype.forEach.call(root.querySelectorAll(selector), callback); }
    }

    function normalize(root) {
        each(root, '[data-toggle="dropdown"], [data-toggle="modal"], [data-toggle="tab"], [data-toggle="collapse"], [data-dismiss="modal"], [data-dismiss="alert"]', function (element) {
            ['toggle', 'target', 'dismiss', 'parent'].forEach(function (name) {
                var value = element.getAttribute('data-' + name);
                if (value !== null) {
                    element.setAttribute('data-bs-' + name, value);
                    element.removeAttribute('data-' + name);
                }
            });
        });
        each(root, '.btn-default', function (element) {
            element.classList.remove('btn-default');
            element.classList.add('btn-outline-secondary');
        });
        each(root, '.pull-left, .pull-right', function (element) {
            var left = element.classList.contains('pull-left');
            element.classList.remove('pull-left', 'pull-right');
            element.classList.add(left ? 'float-start' : 'float-end');
        });
        each(root, '.pagination > li', function (element) {
            element.classList.add('page-item');
            Array.prototype.forEach.call(element.children, function (child) {
                if (child.tagName === 'A' || child.tagName === 'SPAN') { child.classList.add('page-link'); }
            });
        });
        each(root, '.keep-open > [data-bs-toggle="dropdown"]', function (element) {
            element.setAttribute('data-bs-auto-close', 'outside');
        });
        each(root, '.modal .close[data-bs-dismiss]', function (element) {
            element.classList.replace('close', 'btn-close');
            element.setAttribute('aria-label', '关闭');
            element.textContent = '';
        });
        each(root, '#side-menu .collapse.in:not(.show):not(.collapsing)', function (element) {
            element.classList.add('show');
        });
    }

    function configureVendors() {
        if ($.fn.bootstrapTable && $.fn.bootstrapTable.defaults) {
            $.extend($.fn.bootstrapTable.defaults, {
                iconsPrefix: 'fa',
                icons: {
                    paginationSwitchDown: 'fa-chevron-down', paginationSwitchUp: 'fa-chevron-up',
                    refresh: 'fa-refresh', toggle: 'fa-list-alt', columns: 'fa-th',
                    detailOpen: 'fa-plus', detailClose: 'fa-minus'
                }
            });
        }
        if ($.fn.treegrid && $.fn.treegrid.defaults) {
            $.extend($.fn.treegrid.defaults, {
                expanderExpandedClass: 'fa fa-chevron-down', expanderCollapsedClass: 'fa fa-chevron-right'
            });
        }
        if ($.fn.bootstrapTreeTable && $.fn.bootstrapTreeTable.defaults) {
            $.extend($.fn.bootstrapTreeTable.defaults, {
                expanderExpandedClass: 'fa fa-chevron-down', expanderCollapsedClass: 'fa fa-chevron-right'
            });
        }
    }

    function installBridges() {
        ['Alert', 'Button', 'Carousel', 'Collapse', 'Dropdown', 'Modal', 'Popover', 'ScrollSpy', 'Tab', 'Toast', 'Tooltip'].forEach(function (name) {
            var Component = bootstrap[name], method = name.toLowerCase();
            var bridge = function (option) {
                var args = Array.prototype.slice.call(arguments);
                if (option === 'destroy' && (name === 'Tooltip' || name === 'Popover')) { args[0] = 'dispose'; }
                this.each(function () { normalize(this); });
                configureVendors();
                if (name === 'Tooltip') {
                    // Summernote attaches a tooltip to its dropdown buttons. Bootstrap 5
                    // stores only one component per element; retain the native title
                    // on these controls so Dropdown can register and close normally.
                    var eligible = this.filter(function () {
                        var excluded = this.matches('.dropdown-toggle, [data-bs-toggle="dropdown"], [data-bs-toggle="tab"], [data-bs-toggle="collapse"], [data-bs-toggle="modal"]');
                        if (excluded && !this.hasAttribute('aria-label')) {
                            var title = this.getAttribute('title');
                            if (title) { this.setAttribute('aria-label', title); }
                        }
                        return !excluded;
                    });
                    Component.jQueryInterface.apply(eligible, args);
                    return this;
                }
                return Component.jQueryInterface.apply(this, args);
            };
            bridge.Constructor = Component;
            $.fn[method] = bridge;
        });
    }

    // Old content.js initializes tooltips while the parser is still running.
    // Bootstrap's own jQuery registration happens later at DOMContentLoaded.
    installBridges();
    document.addEventListener('DOMContentLoaded', function () {
        installBridges();
        configureVendors();
        normalize(document);
        var observer = new MutationObserver(function (records) {
            records.forEach(function (record) {
                if (record.type === 'attributes') {
                    if (record.target.matches('#side-menu .collapse.in:not(.show):not(.collapsing)')) { normalize(record.target); }
                    return;
                }
                Array.prototype.forEach.call(record.addedNodes, function (node) {
                    if (node.nodeType === 1) { normalize(node); }
                });
            });
        });
        observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
    });
    // Configure icons before app $(document).ready() table initialization.
    $(function () { configureVendors(); normalize(document); });
    document.addEventListener('shown.bs.collapse', function (event) {
        if (event.target.closest('#side-menu')) { event.target.classList.add('in'); }
    });
    document.addEventListener('hidden.bs.collapse', function (event) {
        if (event.target.closest('#side-menu')) { event.target.classList.remove('in'); }
    });
    // The retained table builds dropdowns synchronously during a click callback.
    document.addEventListener('click', function (event) {
        var element = event.target.closest('[data-toggle], [data-dismiss]');
        if (element) { normalize(element.parentNode); }
    }, true);
}(window, document));

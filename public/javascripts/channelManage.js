var PROJECT_OWNER = null;
var PROJECT_SLUG = null;

function rgbToHex(rgb) {
    var parts = rgb.match(/^rgb\((\d+),\s*(\d+),\s*(\d+)\)$/);
    delete(parts[0]);
    for (var i = 1; i <= 3; ++i) {
        parts[i] = parseInt(parts[i]).toString(16);
        if (parts[i].length == 1) {
            parts[i] = '0' + parts[i];
        }
    }
    return '#' + parts.join('');
}

function getModal() {
    return $('#channel-settings');
}

var onCustomSubmit = function(toggle, channelName, channelHex, title, submit) {
    // Called when a channel is being edited before project creation
    var publishForm = $('#form-publish');
    $('#channel-name').text(channelName).css('background-color', channelHex);
    publishForm.find('.channel-input').val(channelName);
    publishForm.find('.channel-color-input').val(channelHex);
    getModal().modal('hide');
    initChannelManager(toggle, channelName, channelHex, title, null, null, submit);
};

function initChannelDelete(toggle, channelName, versionCount) {
    $(toggle).off('click');
    $(toggle).click(function() {
        var url = '/' + PROJECT_OWNER + '/' + PROJECT_SLUG + '/channels/' + channelName + '/delete';
        var modal = $('#modal-delete');
        modal.find('.modal-footer').find('a').attr('href', url);
        modal.find('.version-count').text(versionCount);
    });
}

function initChannelManager(toggle, channelName, channelHex, title, call, method, submit) {
    $(toggle).off('click'); // Unbind previous click handlers
    $(toggle).click(function() {
        var modal = getModal();
        var preview = modal.find('.preview');
        var submitInput = modal.find('input[type="submit"]');

        // Update modal attributes
        modal.find('.color-picker').css('color', channelHex);
        modal.find('.modal-title').text(title);
        preview.css('background-color', channelHex).text(channelName);

        // Set input values
        modal.find('.channel-color-input').val(channelHex);
        modal.find('.channel-input').val(channelName);

        // Only show preview when there is input
        if (channelName.length > 0) {
            preview.show();
        } else {
            preview.hide();
        }

        submitInput.val(submit);
        if (call == null && method == null) {
            // Redirect form submit to client
            submitInput.off('click'); // Unbind existing click handlers
            submitInput.click(function(event) {
                event.preventDefault();
                submitInput.submit();
            });

            submitInput.off('submit'); // Unbind existing submit handlers
            submitInput.submit(function(event) {
                event.preventDefault();
                var modal = getModal();
                onCustomSubmit(toggle, modal.find('.channel-input').val(), modal.find('.channel-color-input').val(), title, submit);
            });
        } else {
            // Set form action
            modal.find('form').attr('action', call).attr('method', method);
        }
    });
}

function initModal() {
    var modal = getModal();
    // Update the preview within the popover when the name is updated
    modal.find('.channel-input').on('input', function() {
        var val = $(this).val();
        var preview = getModal().find('.preview');
        if (val.length == 0) {
            preview.hide();
        } else {
            preview.show().text(val);
        }
    });
    initColorPicker();
}

function initColorPicker() {
    var modal = getModal();
    // Initialize popover to stay opened when hovered over
    modal.find(".color-picker").popover({
        html: true,
        trigger: 'manual',
        container: $(this).attr('id'),
        placement: 'right',
        content: function() {
            return getModal().find(".popover-color-picker").html();
        }
    }).on('mouseenter', function () {
        var _this = this;
        $(this).popover('show');
        $(this).siblings(".popover").on('mouseleave', function () {
            $(_this).popover('hide');
        });
    }).on('mouseleave', function () {
        var _this = this;
        setTimeout(function () {
            if (!$('.popover:hover').length) {
                $(_this).popover('hide')
            }
        }, 100);
    });

    // Update colors when new color is selected
    $(document).on('click', '.channel-id', function() {
        var color = $(this).css("color");
        var modal = getModal();
        modal.find('.channel-color-input').val(rgbToHex(color));
        modal.find('.color-picker').css('color', color);
        modal.find('.preview').css('background-color', color);
    });
}

$(function() {
    initModal();
});

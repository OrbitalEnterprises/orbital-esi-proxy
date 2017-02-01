(function() {
var esiProxyDialog = angular.module('esiProxyDialog', ['ngSanitize']);

var MsgRequest = function(id, dtype, msg, buttons, delay, cb) {
  switch (dtype) {
  case 'warning':
    this.dialogClass = 'warning';
    break;
  case 'error':
    this.dialogClass = 'danger';
    break;
  case 'info':
  default:
    this.dialogClass = 'info';
    break;
  }

  this.id = id;
  this.messageType = angular.uppercase(dtype);
  this.message = msg;
  this.buttons = buttons || [];
  this.delay = delay || 20;
  this.progress = 0;
  this.startProgress = this.delay;
  this.cb = cb || angular.noop;
};

var DialogRequest = function(id, dtype, msg, buttons, delay, cb) {
  switch (dtype) {
  case 'warning':
    this.dialogClass = 'warning';
    break;
  case 'error':
    this.dialogClass = 'danger';
    break;
  case 'info':
  default:
    this.dialogClass = 'info';
    break;
  }

  this.id = id;
  this.messageType = angular.uppercase(dtype);
  this.message = msg;
  this.buttons = buttons || [];
  this.delay = delay || -1;
  this.progress = 0;
  this.startProgress = this.delay;
  this.nodelay = this.delay < 0;
  this.cb = cb || angular.noop;
  if (this.buttons.length == 0) this.buttons.push('ok');
};

/* Dialog Controller for dialog display area */
esiProxyDialog.controller('ESIProxyDialogCtrl', ['$scope', '$timeout', '$sce',
  function($scope, $timeout, $sce) {
    $scope.visibleMessage = null;
    $scope.visibleDialog = null;
    $scope.pendingItems = [];
    $scope.lastItemLoop = 0;
    // Trust message content.
    $scope.$watch('visibleDialog.message', function() {
      if ($scope.visibleDialog != null)
        $scope.renderVisibleDialog = $sce.trustAsHtml($scope.visibleDialog.message);
    });
    $scope.$watch('visibleMessage.message', function() {
      if ($scope.visibleMessage != null)
        $scope.renderVisibleMessage = $sce.trustAsHtml($scope.visibleMessage.message);
    });
    // Center message display properly.
    var centerMessage = function() {
      $('#esi-proxy-message').position({
        of: '#nav-bar',
        at: 'center bottom-40%',
        my: 'center top'
      });
    };
    // Update current message or dialog.
    var refreshItem = function() {
      var now = Date.now();
      var timeout = Math.min(now - $scope.lastItemLoop, 100);
      var cur = null;
      var isMsg = false;
      if ($scope.visibleMessage != null) {
        cur = $scope.visibleMessage;
        isMsg = true;
      } else {
        cur = $scope.visibleDialog;
        isMsg = false;
      }
      // First process any currently displayed item.
      if (cur != null) {
        // Decrement delay timer if at least 100 millis has passed.
        if (now - cur.lastUpdate >= 100) {
          cur.lastUpdate = now;
          cur.delay = cur.delay - 0.1;
          if (cur.delay >= 0) cur.progress = (cur.startProgress - cur.delay)/cur.startProgress * 100;
        }
        // Recenter.
        if (isMsg) centerMessage();
        // Timer expired?  Remove and invoke callback.
        if (cur.delay < 0) {
          if (isMsg)
            $scope.handleMessageComplete(cur.id, cur.cb, cur.buttons.length > 0 ? 0 : -1);
          else if (!cur.nodelay)
            $scope.handleDialogComplete(cur.id, cur.cb, cur.buttons.length > 0 ? 0 : -1);
        }
      }
      // Check whether we need to start displaying a new item.
      if ($scope.visibleMessage == null && $scope.visibleDialog == null && $scope.pendingItems.length > 0) {
        var next = $scope.pendingItems.shift();
        next.lastUpdate = Date.now();
        timeout = 10;
        if (next instanceof MsgRequest) {
          $scope.visibleMessage = next;
        } else {
          $scope.visibleDialog = next;
          $('#esi-proxy-dialog').modal('show');
        }
      }
      // Schedule our next update if we're still showing a message.
      if ($scope.visibleMessage != null || $scope.visibleDialog != null) $timeout(refreshItem, timeout);
      $scope.lastItemLoop = now;
    };
    // Handle request to queue up a new message to display.
    $scope.$on('AddMessage', function(event, id, dtype, msg, buttons, delay, cb) {
      var msg = new MsgRequest(id, dtype, msg, buttons, delay, cb);
      if ($scope.pendingItems.length == 0) $timeout(refreshItem, 10);
      $scope.pendingItems.push(msg);
    });
    // Handle request to queue up a new dialog to display.
    $scope.$on('AddDialog', function(event, id, dtype, msg, buttons, delay, cb) {
      var msg = new DialogRequest(id, dtype, msg, buttons, delay, cb);
      if ($scope.pendingItems.length == 0) $timeout(refreshItem, 10);
      $scope.pendingItems.push(msg);
    });
    // Handle request to remove a previous message by id.
    $scope.$on('RemoveMessage', function(event, id) {
      if ($scope.visibleMessage != null && $scope.visibleMessage.id == id) {
        $scope.visibleMessage = null;
      } else {
        $scope.pendingItems = $scope.pendingItems.filter(function(test) { return test.id != id; });
      }
      $timeout(refreshItem, 10);
    });
    // Handle request to remove a previous dialog by id.
    $scope.$on('RemoveDialog', function(event, id) {
      if ($scope.visibleDialog != null && $scope.visibleDialog.id == id) {
        $scope.visibleDialog = null;
        $('#esi-proxy-dialog').modal('hide');
      } else {
        $scope.pendingItems = $scope.pendingItems.filter(function(test) { return test.id != id; });
      }
      $timeout(refreshItem, 10);
    });
    // Handle message completion callback.
    $scope.handleMessageComplete = function(id, cb, choice) {
      if ($scope.visibleMessage != null && $scope.visibleMessage.id == id) $scope.visibleMessage = null;
      cb(choice);
      $timeout(refreshItem, 10);
    };
    // Handle dialog completion callback.
    $scope.handleDialogComplete = function(id, cb, choice) {
      if ($scope.visibleDialog != null && $scope.visibleDialog.id == id) {
        $scope.visibleDialog = null;
        $('#esi-proxy-dialog').modal('hide');
      }
      cb(choice);
      $timeout(refreshItem, 10);
    };
  }]);

/** Dialog display service -
 *  1. Dialogs are always modal.
 *  2. Dialogs must always have buttons, the first is always the default.
 *  3. A dialog will auto-disappear if a delay is provided.
 *  4. A dialog which auto disappears will act as if the default button was selected.
 */
esiProxyDialog.factory('DialogService',
    ['$rootScope',
     function($rootScope) {
      var itemID = 1;
      return {
        createDialog: function(dtype, msg, buttons, delay, cb) {
          var id = itemID++;
          $rootScope.$broadcast('AddDialog', id, dtype, msg, buttons, delay, cb);
          return id;
        },
        removeDialog: function(id) {
          $rootScope.$broadcast('RemoveDialog', id);
        },
        createMessage: function(dtype, msg, buttons, delay, cb) {
          var id = itemID++;
          $rootScope.$broadcast('AddMessage', id, dtype, msg, buttons, delay, cb);
          return id;
        },
        removeMessage: function(id) {
          $rootScope.$broadcast('RemoveMessage', id);
        },
        simpleInfoMessage: function(msg, delay, cb) {
          return this.createMessage('info', msg, ['ok'], delay, cb);
        },
        simpleWarnMessage: function(msg, delay, cb) {
          return this.createMessage('warning', msg, ['ok'], delay, cb);
        },
        simpleErrorMessage: function(msg, delay, cb) {
          return this.createMessage('error', msg, ['ok'], delay, cb);
        },
        connectionErrorMessage: function(msg, delay, cb) {
          return this.createMessage('error', 'Connection error while ' + msg + '.  If this problem persists, please contact the site administrator.', ['ok'], delay, cb);
        },
        serverErrorMessage: function(msg, delay, cb) {
          return this.createMessage('error', 'Server error while ' + msg + '.  If this problem persists, please contact the site administrator.', ['ok'], delay, cb);
        },
        ackDialog: function(dtype, msg, delay, cb) {
          return this.createDialog(dtype, msg, ['ok'], delay, cb);
        },
        yesNoDialog: function(dtype, msg, yesIsDefault, cb) {
          yesIsDefault = yesIsDefault || false;
          return this.createDialog(dtype, msg, yesIsDefault ? ['yes', 'no'] : ['no', 'yes'], -1, cb);
        }
      };
    }]);

})();
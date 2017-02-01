/* ESI Proxy Connections Page Module */
(function(){
  var esiProxyConnections = angular.module('esiProxyConnections', ['ngResource', 'ngSanitize', 'ngRoute', 'esiProxyDialog', 'esiProxyServicesWS']);

  // Validation functions.
  var isValidDate = function(str) {
    return /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]$/.test(str);
  };

  var validateKeyExpiry = function(name) {
    if (!name || name.length == 0)
      return false;
    var trimmed = name.trim();
    if (trimmed == 'Never') return true;
    return isValidDate(trimmed);
  };

  var abstractValidator = function(name, popover, validator) {
    return function() {
      return {
        require: 'ngModel',
        restrict: 'A',
        link: function(scope, elm, attrs, ctrl) {
          // if (!ngModel) return;
          ctrl.$parsers.unshift(function(viewValue) {
            var isValid = validator(viewValue);
            var status = isValid ? 'hide' : 'show';
            $('#' + popover).popover(status);
            ctrl.$setValidity(name, isValid);
            return viewValue;
          });
        }
      };
    }
  };

  // Validators
  esiProxyConnections.directive('validatekeyexpiry', abstractValidator('validatekeyexpiry', 'expireInput', validateKeyExpiry));

  esiProxyConnections.controller('ConnectionsCtrl',
      ['$scope', '$location', '$filter', 'DialogService', 'AccountWSService', 'UserCredentialsService',
       function($scope, $location, $filter, DialogService, AccountWSService, UserCredentialsService) {
        $scope.sectionName = "Connection List";
        $scope.loading = false;
        $scope.connectionList = [];
        // State for create connection dialog
        $scope.serverType = 'latest';
        $scope.expiryDate = 'Never';
        $scope.currentScopeSelection = [];
        // Reload key list.
        $scope.reloadList = function() {
          $scope.loading = true;
          AccountWSService.getUser().then(function(uval) {
            if (uval == null) {
              $scope.$apply(function() {
                // Not logged in, redirect
                $location.path('/main');
              });
              return;
            }
            // Valid user, look for key list
            AccountWSService.getAccessKeys().then(function(result) {
              $scope.$apply(function() {
                $scope.loading = false;
                $scope.connectionList = result;
                // Compute displayable scope list.
                for (var i=0; i < $scope.connectionList.length; i++) {
                  var scope_title = "Authorized Scopes:\n";
                  var scopes = $scope.connectionList[i].scopes.split(' ');
                  for (var j=0; j < scopes.length; j++) {
                    scope_title += scopes[j] + '\n';
                  }
                  // Remove the trailing newline.
                  if (scope_title.length > 0) {
                    scope_title = scope_title.substring(0, scope_title.length - 1);
                  }
                  $scope.connectionList[i].displayScopes = scope_title;
                }
              });
            }).catch(function(err) {
              $scope.$apply(function() {
                $scope.loading = false;
                DialogService.connectionErrorMessage('loading connection list: ' + err.errorMessage, 20);
              });
            });
          }).catch(function(err) {
            $scope.$apply(function() {
              // Assume not logged in and redirect
              $location.path('/main');
            });
          });
        };
        // Delete a connection
        $scope.deleteConnection = function(key) {
          DialogService.yesNoDialog('warning', 'Really delete connection?', false, function(answer) {
            if (answer == 1) {
              var info = DialogService.simpleInfoMessage('Deleting connection...');
              AccountWSService.deleteAccessKey(key).then(function(result) {
                $scope.$apply(function() {
                  DialogService.removeMessage(info);
                  $scope.reloadList();
                });
              }).catch(function(err) {
                $scope.$apply(function() {
                  DialogService.removeMessage(info);
                  DialogService.connectionErrorMessage('removing connection: ' + err.errorMessage, 20);
                });
              });
            }
          })
        };
        // Retrieve scopes for a server type
        $scope.getScopeList = function(serverType, setter) {
          AccountWSService.getScopes(serverType).then(function(result) {
            $scope.$apply(function() {
              // Result is a map: scopeName -> scopeDefinition
              // Transform to a list of objects before returning
              scopeList = [];
              for (var sc in result) {
                if (result.hasOwnProperty(sc)) scopeList.push({value: sc, description: result[sc]});
              }
              setter(scopeList);
            });
          }).catch(function(err) {
            $scope.$apply(function() {
              $('#createConnection').modal('hide');
              DialogService.connectionErrorMessage('retrieving scope list: ' + err.errorMessage, 20);
            });
          })
        };
        // Popup to change expiry date for a connection
        $scope.changeConnectionExpiry = function(index) {
          if (index < 0 || index >= $scope.connectionList.length) return;
          $scope.modConnection = $scope.connectionList[index];
          $scope.expiryDate = $scope.modConnection.expiry <= 0 ? 'Never' : $filter('date')($scope.modConnection.expiry, "yyyy-MM-dd");
          $('#createConnection').modal({
            backdrop: 'static',
            keyboard: false
          });
        };
        // Update scope list when server type changes
        $scope.updateDialogScope = function() {
          var info = DialogService.simpleInfoMessage("Retrieving scope list...", 10);
          if ($scope.serverType in $scope.scopeCache) {
            // Cached, used the cached value
            $scope.currentScope = $scope.scopeCache[$scope.serverType];
            $scope.currentScopeSelection = {};
            DialogService.removeMessage(info);
          } else {
            $scope.currentScope = [];
            $scope.currentScopeSelection = {};
            var scopeName = $scope.serverType;
            $scope.getScopeList($scope.serverType, function(newScopeList) {
              DialogService.removeMessage(info);
              $scope.currentScope = $scope.scopeCache[scopeName] = newScopeList;
            });
          }
        };
        // Reset scopes
        $scope.clearAllScopes = function() {
          $scope.currentScopeSelection = {};
        };
        // Select all scopes
        $scope.selectAllScopes = function() {
          for (var i = 0; i < $scope.currentScope.length; i++) {
            $scope.currentScopeSelection[$scope.currentScope[i].value] = true;
          }
        };
        // Sanity chaeck invalid form
        $scope.isFormInvalid = function() {
          if ($scope.expiryDate == 'Never') return true;
          return isValidDate($scope.expiryDate.trim());
        }
        // Save new or changed connection
        $scope.saveConnection = function() {
          if ($scope.modConnection != null) {
            // Modifying existing connection
            var expiry = -1;
            $scope.expiryDate = $scope.expiryDate.trim();
            if ($scope.expiryDate != 'Never') expiry = (new Date($scope.expiryDate)).getTime();
            var changedConn = {
                kid: $scope.modConnection.kid,
                expiry: expiry
            }
            AccountWSService.saveAccessKey(changedConn).then(function(result) {
              // Success, refresh list
              $scope.reloadList();
            }).catch(function(err) {
              // Fail, show error message
              $scope.$apply(function() {
                DialogService.connectionErrorMessage('changing connection expiry: ' + err.errorMessage, 20);
              });
            });
          } else {
            // Creating new connection
            var expiry = -1;
            $scope.expiryDate = $scope.expiryDate.trim();
            if ($scope.expiryDate != 'Never') expiry = (new Date($scope.expiryDate)).getTime();
            var scopeList = [];
            for (var sc in $scope.currentScopeSelection) {
              if ($scope.currentScopeSelection.hasOwnProperty(sc)) scopeList.push(sc);
            }
            var newConn = {
                kid: -1,
                expiry: expiry,
                serverType: $scope.serverType,
                scopes: scopeList.join(' ')
            };
            AccountWSService.saveAccessKey(newConn).then(function(result) {
              // Success, refresh list
              $scope.reloadList();
            }).catch(function(err) {
              // Fail, show error message
              $scope.$apply(function() {
                DialogService.connectionErrorMessage('saving new connection: ' + err.errorMessage, 20);
              });
            });
          }
        };
        // Popup to create new connection
        $scope.create = function() {
          $scope.modConnection = null;
          $scope.scopeCache = {};
          $scope.serverType = 'latest';
          $scope.expiryDate = 'Never';
          $scope.currentScopeSelection = {};
          $scope.currentScope = [];
          $scope.updateDialogScope();
          $('#createConnection').modal({
            backdrop: 'static',
            keyboard: false
          });
        };
        // Init
        $('#expireInput').datepicker({dateFormat: "yy-mm-dd"});
        $('#changeExpireInput').datepicker({dateFormat: "yy-mm-dd"});
        $scope.reloadList();
      }]);


})();

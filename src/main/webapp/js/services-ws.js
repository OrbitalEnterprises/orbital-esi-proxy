/* Proxy Services */
(function() {
var servicesWS = angular.module('esiProxyServicesWS', ['esiProxyRemoteServices']);

/**
 * Service for retrieving build and version info.
 */
servicesWS.factory('ReleaseService', ['SwaggerService',
  function(SwaggerService) {
    return {
      'buildDate' : function() {
        return SwaggerService.getSwagger()
        .then(function (swg) {
          return swg.Services.buildDate({}, {})
          .then(function(result) {
            return result.status == 200 ? result.obj['buildDate'] : "";
          })
          .catch(function(error) {
            console.log(error);
            return "";
          });
        });
      },
      'version' : function() {
        return SwaggerService.getSwagger()
        .then(function (swg) {
          return swg.Services.version({}, {})
          .then(function(result) {
            return result.status == 200 ? result.obj['version'] : "";
          })
          .catch(function(error) {
            console.log(error);
            return "";
          });
        });
      }
    };
 }]);


/**
 * Service for sharing authentication state among all controllers.
 */
servicesWS.factory('AccountWSService', ['SwaggerService',
  function(SwaggerService) {
    return {
      'getAccessKeys' : function() {
        return SwaggerService.getSwagger()
        .then(function (swg) {
          return swg.Services.getAccessKeys({}, {})
          .then(function(result) {
            return result.obj;
          }).catch(handleRemoteResponse);
        });
      },
      'saveAccessKey' : function(key) {
        return SwaggerService.getSwagger()
        .then(function (swg) {
          return swg.Services.saveAccessKey({key: key}, {})
          .then(function(result) {
            return result.obj;
          }).catch(handleRemoteResponse);
        });
      },
      'deleteAccessKey' : function(kid) {
        return SwaggerService.getSwagger()
        .then(function (swg) {
          return swg.Services.deleteAccessKey({kid: kid}, {})
          .then(function(result) {
            return true;
          }).catch(handleRemoteResponse);
        });
      },
      'getUser' : function() {
        return SwaggerService.getSwagger()
        .then(function (swg) {
          return swg.Services.getUser({}, {})
          .then(function(result) {
            return result.obj;
          }).catch(handleRemoteResponse);
        });
      },
      'getUserLastSource' : function(uid) {
        return SwaggerService.getSwagger()
        .then(function (swg) {
          return swg.Services.getUserLastSource({uid: uid || -1}, {})
          .then(function(result) {
            return result.obj;
          }).catch(handleRemoteResponse);
        });
      },
      'getScopes' : function(server) {
        return SwaggerService.getSwagger()
        .then(function (swg) {
          return swg.Services.getScopes({server: server}, {})
          .then(function(result) {
            return result.obj;
          }).catch(handleRemoteResponse);
        });
      }
    };
 }]);

/**
 * Service to collect and periodically update user credentials.  Changes in credentials are broadcast as an event.
 */
servicesWS.factory('UserCredentialsService', ['$rootScope', '$timeout', 'AccountWSService',
  function($rootScope, $timeout, AccountWSService) {
    var userInfo = null;
    var userSource = null;
    var update = function(user, source) {
      $rootScope.$apply(function() {
        if (user) $rootScope.$broadcast('UserInfoChange', userInfo);
        if (source) $rootScope.$broadcast('UserSourceChange', userSource);
      });
    };
    var updateUserCredentials = function() {
      AccountWSService.getUser().then(function (val) {
        if (val != null) {
          userInfo = val;
          update(true, false);
        }
      }).catch(function() {
        // Reset user on any error
        userInfo = null;
        update(true, false);
      });
      AccountWSService.getUserLastSource().then(function (val) {
        if (val != null) {
          userSource = val;
          update(false, true);
        }
      }).catch(function() {
        // Reset source on any error
        userSource = null;
        update(false, true);
      });
      $timeout(updateUserCredentials, 1000 * 60 * 3);
    };
    updateUserCredentials();
    return {
      'getUser' : function() { return userInfo; },
      'getUserSource' : function() { return userSource; }
    };
  }]);

})();

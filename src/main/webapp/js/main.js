/* Proxy Main Page Module */
(function(){
  var esiProxyMain = angular.module('esiProxyMain', ['ngResource', 'ngSanitize']);

  esiProxyMain.controller('MainCtrl',
      ['$scope', '$sce', '$timeout',
       function($scope, $sce, $timeout) {
    $scope.sectionName = "Home";
  }]);


})();

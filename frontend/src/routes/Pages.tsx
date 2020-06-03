import * as React from 'react';
import { Route, Switch } from 'react-router-dom';

import Login from '../components/login/Index';
import NotFound from '../components/NotFound';
import Home from '../components/home/Index';
import Activity from '../components/activity/Index';
import BankAccounts from '../components/bank-accounts/Index';

import AuthenticatedRoute from './AuthenticatedRoute';
import UnauthenticatedRoute from './UnauthenticatedRoute';

const Pages = () => {
  return (
    <Switch>
      <UnauthenticatedRoute path="/" exact component={Login} />
      <UnauthenticatedRoute path="/login" exact component={Login} />
      <AuthenticatedRoute path="/" exact component={Home} />
      <AuthenticatedRoute path="/home" exact component={Home} />
      <AuthenticatedRoute path="/activity" exact component={Activity} />
      <AuthenticatedRoute
        path="/bank-accounts"
        exact
        component={BankAccounts}
      />
      <Route component={NotFound} />
    </Switch>
  );
};

export default Pages;

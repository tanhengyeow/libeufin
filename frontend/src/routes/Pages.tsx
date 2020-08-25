/*
 This file is part of GNU Taler
 (C) 2020 Taler Systems S.A.

 GNU Taler is free software; you can redistribute it and/or modify it under the
 terms of the GNU General Public License as published by the Free Software
 Foundation; either version 3, or (at your option) any later version.

 GNU Taler is distributed in the hope that it will be useful, but WITHOUT ANY
 WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 A PARTICULAR PURPOSE.  See the GNU General Public License for more details.

 You should have received a copy of the GNU General Public License along with
 GNU Taler; see the file COPYING.  If not, see <http://www.gnu.org/licenses/>
 */

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

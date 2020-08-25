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

import React from 'react';
import { connect } from 'react-redux';
import { Route, Router } from 'react-router-dom';
import history from './history';
import Pages from './routes/Pages';
import { checkAuthentication } from './actions/auth';
import { Store } from './types';
import './App.less';

interface Props {
  checkAuthenticationConnect: () => void;
  isAuthenticated: boolean | null;
}

const App = ({ checkAuthenticationConnect, isAuthenticated }: Props) => {
  React.useEffect(() => {
    checkAuthenticationConnect();
  });

  const app =
    isAuthenticated !== null ? (
      <Router history={history}>
        <Route component={Pages} />
      </Router>
    ) : null;

  return <div className="App">{app}</div>;
};

const mapStateToProps = (state: Store) => ({
  ...state,
  isAuthenticated: state.isAuthenticated,
});

const mapDispatchToProps = {
  checkAuthenticationConnect: checkAuthentication,
};

export default connect(mapStateToProps, mapDispatchToProps)(App);

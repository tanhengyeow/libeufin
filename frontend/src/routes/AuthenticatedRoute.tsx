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

/* eslint-disable @typescript-eslint/no-explicit-any */
import * as React from 'react';
import { connect } from 'react-redux';
import { Route } from 'react-router-dom';
import history from '../history';
import { Store } from '../types';

import './Layout.less';
import NavBar from '../components/navbar/Index';
import Footer from '../components/footer/Index';

interface Props {
  exact?: boolean;
  isAuthenticated: boolean | null;
  path: string;
  component: React.ComponentType<any>;
}

const AuthenticatedRoute = ({
  component: Component,
  isAuthenticated,
  ...otherProps
}: Props) => {
  if (isAuthenticated === false) {
    history.push('/login');
  }

  return (
    <>
      <div className="container">
        <NavBar />
        <Route
          render={() => (
            <>
              <Component {...otherProps} />
            </>
          )}
        />
      </div>
      <Footer />
    </>
  );
};

const mapStateToProps = (state: Store) => ({
  ...state,
  isAuthenticated: state.isAuthenticated,
});

export default connect(mapStateToProps)(AuthenticatedRoute);

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

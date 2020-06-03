/* eslint-disable @typescript-eslint/no-explicit-any */
import * as React from 'react';
import { connect } from 'react-redux';
import { Route } from 'react-router-dom';

import history from '../history';
import { Auth } from '../types';

interface Props {
  exact?: boolean;
  isAuthenticated: boolean | null;
  path: string;
  component: React.ComponentType<any>;
}

const UnauthenticatedRoute = ({
  component: Component,
  isAuthenticated,
  ...otherProps
}: Props) => {
  if (isAuthenticated === true) {
    history.push('/home');
  }

  return (
    <>
      <Route
        render={() => (
          <>
            <Component {...otherProps} />
          </>
        )}
      />
    </>
  );
};

const mapStateToProps = (state: Auth) => ({
  isAuthenticated: state.isAuthenticated,
});

export default connect(mapStateToProps)(UnauthenticatedRoute);

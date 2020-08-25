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

import React, { useState } from 'react';
import { Tabs } from 'antd';
import PaymentInitiationList from './payments/PaymentInitiationList';
import TransactionsList from './transaction-history/TransactionsList';

import './Activity.less';
const { TabPane } = Tabs;

const Activity = () => {
  const [visible, setVisible] = useState(false);

  const showDrawer = () => {
    setVisible(true);
  };
  const onClose = () => {
    setVisible(false);
  };

  return (
    <div className="activity">
      <Tabs defaultActiveKey="1" type="card" size="large">
        <TabPane tab="Payments" key="1">
          <PaymentInitiationList
            visible={visible}
            onClose={onClose}
            showDrawer={showDrawer}
          />
        </TabPane>
        <TabPane tab="Transaction History" key="2">
          <TransactionsList />
        </TabPane>
        <TabPane tab="Taler View" key="3">
          Taler View
        </TabPane>
      </Tabs>
    </div>
  );
};

export default Activity;
